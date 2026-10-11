package handshake.wallet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ties WalletCovenantCache and WalletAuctionMonitor to an actual,
 * running sync loop: fetches whatever full blocks the currently-watched
 * auctions still need (via a dedicated, unfiltered connection -- see
 * this whole project's own design discussion for why this stays
 * separate from the primary filtered balance-sync connection), feeds
 * them through the cache, updates the monitor, and drives real
 * auto-reveal broadcasting once a bid's deadline is close.
 *
 * Deliberately uses a small SAFETY_MARGIN_BLOCKS before attempting a
 * reveal right at the computed bidding-close height, rather than firing
 * at the exact boundary -- a rare but real chain reorg right at that
 * boundary could otherwise mean a reveal gets broadcast one block too
 * early (rejected by real consensus rules, since the bidding period
 * hasn't technically closed from the network's own perspective yet) and
 * has to be retried. Revealing a few blocks into the window costs
 * nothing (REVEAL has no urgency to be early, only a hard deadline not
 * to be late), so there's no real downside to the margin.
 */
public class WalletAuctionEngine {

    private static final int SAFETY_MARGIN_BLOCKS = 3;
    private static final int MAX_BLOCKS_PER_SYNC_PASS = 50; // bound how much one call fetches, so a big backlog doesn't block the caller indefinitely

    private final WalletCovenantCache cache;
    private final WalletAuctionMonitor monitor;
    private final WalletBidManager bidManager;

    private final List<byte[]> signedRevealsToRelayBroadcast = new ArrayList<>();
    private final List<WalletAuctionMonitor.Alert> forfeitedBidAlerts = new ArrayList<>();

    public WalletAuctionEngine(WalletCovenantCache cache, WalletAuctionMonitor monitor, WalletBidManager bidManager) {
        this.cache = cache;
        this.monitor = monitor;
        this.bidManager = bidManager;
    }

    /** Every height any currently-watched auction still needs, that
     *  isn't already cached -- the union across all of them, so a block
     *  relevant to two simultaneous auctions is only ever fetched once.
     *  Bounded to the real chain (never below 0, never above the
     *  current tip -- there's nothing to fetch yet beyond it). */
    public List<Integer> determineNeededHeights(java.util.Collection<WalletAuctionMonitor.AuctionState> watchedAuctions,
                                                int currentTip) {
        java.util.TreeSet<Integer> needed = new java.util.TreeSet<>();
        for (WalletAuctionMonitor.AuctionState state : watchedAuctions) {
            int windowStart = Math.max(0, state.openHeight);
            int windowEnd = Math.min(currentTip, state.openHeight + WalletCovenantCache.WINDOW_BLOCKS);
            for (int h = windowStart; h <= windowEnd; h++) {
                if (!cache.hasBlock(h)) needed.add(h);
            }
        }
        return new ArrayList<>(needed);
    }

    /** Fetches and ingests up to MAX_BLOCKS_PER_SYNC_PASS still-needed
     *  blocks over the given connection (which must NOT have a filter
     *  installed -- a filtered connection would silently return
     *  MERKLEBLOCK responses instead of full blocks here), then updates
     *  the monitor and checks every pending bid for an approaching or
     *  already-passed reveal deadline.
     *
     *  Returns the alerts generated this pass (both from the monitor
     *  and from this call's own forfeiture detection) -- the caller
     *  (eventually the GUI) is expected to drain and surface these;
     *  this method doesn't hold on to them beyond returning them once. */
    public List<WalletAuctionMonitor.Alert> syncAndCheck(WalletPeerConnection unfilteredConn, WalletHeaderChain headerChain,
                                                         java.util.Collection<WalletAuctionMonitor.AuctionState> watchedAuctions,
                                                         WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
                                                         long feeRatePerByte) throws Exception {
        int currentTip = headerChain.tipHeight();
        List<Integer> needed = determineNeededHeights(watchedAuctions, currentTip);

        int fetchedThisPass = 0;
        for (int height : needed) {
            if (fetchedThisPass >= MAX_BLOCKS_PER_SYNC_PASS) break;
            byte[] header = headerChain.headerAt(height);
            if (header == null) continue; // not synced this far in headers yet either
            byte[] blockHash = HeaderUtil.hash(header);

            unfilteredConn.sendGetData(List.of(blockHash), 2); // itemType 2 = BLOCK
            byte[] msg = unfilteredConn.readMessage(30_000);
            if (msg == null || WalletPeerConnection.getMessageType(msg) != WalletPeerConnection.MSG_BLOCK) {
                continue; // skip this one for now; determineNeededHeights() will offer it again next pass
            }
            byte[] rawBlock = Arrays.copyOfRange(msg, 9, msg.length);
            cache.ingestBlock(rawBlock, height);
            fetchedThisPass++;
        }

        monitor.update(cache, currentTip);
        List<WalletAuctionMonitor.Alert> alerts = new ArrayList<>(monitor.drainAlerts());

        checkAutoReveal(currentTip, utxoSet, addrMgr, accountKey, feeRatePerByte, alerts);

        return alerts;
    }

    /** Checks every still-pending bid against its real deadline and
     *  either broadcasts a reveal (once safely past bidding-close) or
     *  gives up and reports forfeiture (once past reveal-close with
     *  nothing ever broadcast) -- there's nothing left to do for a bid
     *  in that second case, the lockup is gone by design. */
    void checkAutoReveal(int currentHeight, WalletUtxoSet utxoSet, WalletAddressManager addrMgr,
                         HDKey accountKey, long feeRatePerByte, List<WalletAuctionMonitor.Alert> alertsOut) {
        for (WalletAuctionMonitor.AuctionState state : monitor.watchedAuctionsSnapshot()) {
            WalletBidManager.PendingBid bid = bidManager.getPendingBid(state.nameHash);
            if (bid == null || bid.bidTxid == null) continue; // nothing pending, or BID itself not confirmed/broadcast yet

            int biddingCloseHeight = state.openHeight + 37 + 720;
            int revealCloseHeight = biddingCloseHeight + 1440;

            if (currentHeight >= revealCloseHeight) {
                // Too late -- this cannot be recovered, by design (see
                // WalletBidManager's own top comment). Stop tracking it
                // and make sure this is surfaced clearly rather than
                // silently dropped.
                alertsOut.add(new WalletAuctionMonitor.Alert(
                        WalletAuctionMonitor.AlertType.LOST_AUCTION, bid.name, bid.nameHash,
                        "The reveal deadline for \"" + bid.name + "\" has passed without a reveal ever being "
                                + "broadcast -- this bid's entire lockup (" + bid.lockup + ") is permanently forfeited."));
                bidManager.removePendingBid(state.nameHash);
                continue;
            }

            if (currentHeight >= biddingCloseHeight + SAFETY_MARGIN_BLOCKS) {
                WalletCovenantTxBuilder.BuildResult revealResult = WalletCovenantTxBuilder.buildReveal(
                        utxoSet, addrMgr, accountKey, bid, feeRatePerByte);
                if (revealResult.success) {
                    signedRevealsToRelayBroadcast.add(revealResult.signedTx);
                    // Not removed from bidManager here -- WalletBidManager
                    // still needs this record until the REVEAL itself is
                    // confirmed on chain (in case this broadcast attempt
                    // fails or needs retrying); the wallet's own sync
                    // (WalletUtxoSet.applyTransaction on the confirmed
                    // REVEAL) is what should eventually clear it.
                } else {
                    alertsOut.add(new WalletAuctionMonitor.Alert(
                            WalletAuctionMonitor.AlertType.REVEAL_DEADLINE_APPROACHING, bid.name, bid.nameHash,
                            "Automatic reveal for \"" + bid.name + "\" failed to build (" + revealResult.error
                                    + ") -- manual attention needed before the deadline."));
                }
            }
        }
    }

    /** Every signed REVEAL built by auto-reveal since the last call,
     *  ready to broadcast -- separated from the building step
     *  deliberately, so broadcasting (which needs a connection, and
     *  might reasonably use a different one than block-fetching) stays
     *  the caller's own responsibility. */
    public List<byte[]> drainSignedRevealsToBroadcast() {
        List<byte[]> result = new ArrayList<>(signedRevealsToRelayBroadcast);
        signedRevealsToRelayBroadcast.clear();
        return result;
    }
}