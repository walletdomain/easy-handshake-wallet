package handshake.wallet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches every currently-active auction the wallet has bid on, using
 * WalletCovenantCache's already-downloaded, already-parsed covenant
 * fragments -- no additional network activity of its own. Produces two
 * kinds of real, actionable output:
 *
 *  1. "May have been outbid" alerts during the bidding period, the
 *     moment a BID transaction (not this wallet's own) shows a lockup
 *     higher than this wallet's own highest lockup for that name.
 *     Deliberately phrased as "may" throughout -- lockup, not real
 *     value, is all that's ever visible before reveal (see this whole
 *     design discussion for why that's a hard protocol limit, not a
 *     gap in this code).
 *
 *  2. A running Vickrey (second-price) computation during the reveal
 *     period, mirroring BlockProcessor's own tracking on the node side
 *     exactly (highest/second-highest, same update rule) -- because
 *     this class watches every bidder's REVEAL for a name, not just
 *     this wallet's own, it's positioned to solve the real gap flagged
 *     earlier: what value buildRegister() should actually use. That's
 *     wired up by whatever calls this class at register time, not
 *     computed here directly -- this class only tracks the numbers.
 */
public class WalletAuctionMonitor {

    public enum AlertType { MAY_HAVE_BEEN_OUTBID, REVEAL_DEADLINE_APPROACHING, LOST_AUCTION, WON_AUCTION }

    public static class Alert {
        public AlertType type;
        public String name;
        public byte[] nameHash;
        public String message;

        Alert(AlertType type, String name, byte[] nameHash, String message) {
            this.type = type; this.name = name; this.nameHash = nameHash; this.message = message;
        }
    }

    public static class AuctionState {
        public byte[] nameHash;
        public String name;
        public int openHeight;
        public long highestRevealed = -1;
        public long secondHighestRevealed = -1; // the real hsd Vickrey "value" -- what REGISTER should actually pay
        public byte[] winningTxid;
        public int winningOutputIndex;
        public byte[] winningAddrHash;
        // Every lockup seen for this name during bidding, keyed by
        // txid:outputIndex, so a re-processed block never double-counts.
        final Map<String, Long> seenLockups = new HashMap<>();
        boolean outbidAlertAlreadyFired = false;
    }

    private final Map<String, AuctionState> watchedAuctions = new HashMap<>();
    private final List<Alert> pendingAlerts = new ArrayList<>();
    private final WalletBidManager bidManager;

    public WalletAuctionMonitor(WalletBidManager bidManager) {
        this.bidManager = bidManager;
    }

    public void watchAuction(String name, byte[] nameHash, int openHeight) {
        AuctionState state = new AuctionState();
        state.nameHash = nameHash;
        state.name = name;
        state.openHeight = openHeight;
        watchedAuctions.put(hex(nameHash), state);
    }

    public void unwatchAuction(byte[] nameHash) {
        watchedAuctions.remove(hex(nameHash));
    }

    /** Re-scans every watched auction's covenant fragments from the
     *  cache, up to the given current height -- call this after every
     *  new block is ingested into the cache. Idempotent: safe to call
     *  repeatedly with the same or a higher height. */
    public void update(WalletCovenantCache cache, int currentHeight) {
        for (AuctionState state : watchedAuctions.values()) {
            List<WalletCovenantCache.CachedCovenantTx> fragments = cache.getFragmentsForName(state.nameHash);

            for (WalletCovenantCache.CachedCovenantTx frag : fragments) {
                if (frag.covenantType == TxParser.COV_BID) {
                    processBidFragment(state, frag);
                } else if (frag.covenantType == TxParser.COV_REVEAL) {
                    processRevealFragment(state, frag);
                }
            }

            checkRevealDeadline(state, currentHeight);
        }
    }

    private void processBidFragment(AuctionState state, WalletCovenantCache.CachedCovenantTx frag) {
        String outpointKey = hex(frag.txid) + ":" + frag.outputIndex;
        if (state.seenLockups.containsKey(outpointKey)) return; // already processed
        state.seenLockups.put(outpointKey, frag.value);

        WalletBidManager.PendingBid ownBid = bidManager.getPendingBid(state.nameHash);
        boolean isOwnBid = ownBid != null && ownBid.bidTxid != null
                && Arrays.equals(ownBid.bidTxid, frag.txid) && ownBid.bidOutputIndex == frag.outputIndex;
        if (isOwnBid) return;

        if (ownBid != null && frag.value > ownBid.lockup && !state.outbidAlertAlreadyFired) {
            state.outbidAlertAlreadyFired = true;
            pendingAlerts.add(new Alert(AlertType.MAY_HAVE_BEEN_OUTBID, state.name, state.nameHash,
                    "A new bid with a higher lockup (" + frag.value + ") was placed for \"" + state.name
                            + "\" -- your current lockup is " + ownBid.lockup
                            + ". You may want to place an additional bid before bidding closes."));
        }
    }

    /** Mirrors BlockProcessor's own REVEAL-processing update rule
     *  exactly (see BlockProcessor.java's COV_REVEAL case): the first
     *  reveal seen becomes the provisional winner with a second-price
     *  of 0; each subsequent reveal either becomes the new highest
     *  (demoting the old highest down to second-price) or, if it's
     *  merely higher than the current second-price without beating the
     *  highest, raises the second-price alone. */
    private void processRevealFragment(AuctionState state, WalletCovenantCache.CachedCovenantTx frag) {
        String outpointKey = hex(frag.txid) + ":" + frag.outputIndex;
        if (state.seenLockups.containsKey("reveal:" + outpointKey)) return;
        state.seenLockups.put("reveal:" + outpointKey, frag.value);

        boolean noWinnerYet = state.winningTxid == null;
        if (noWinnerYet || frag.value > state.highestRevealed) {
            state.secondHighestRevealed = state.highestRevealed;
            state.winningTxid = frag.txid;
            state.winningOutputIndex = frag.outputIndex;
            state.winningAddrHash = frag.addrHash;
            state.highestRevealed = frag.value;
        } else if (frag.value > state.secondHighestRevealed) {
            state.secondHighestRevealed = frag.value;
        }
    }

    private void checkRevealDeadline(AuctionState state, int currentHeight) {
        WalletBidManager.PendingBid ownBid = bidManager.getPendingBid(state.nameHash);
        if (ownBid == null || ownBid.bidTxid == null) return; // nothing pending, or not yet broadcast

        int biddingCloseHeight = state.openHeight + 37 + 720;
        int revealCloseHeight = biddingCloseHeight + 1440;
        int blocksUntilDeadline = revealCloseHeight - currentHeight;

        if (currentHeight >= biddingCloseHeight && blocksUntilDeadline <= 144 && blocksUntilDeadline > 0) {
            // Real alert only -- actual auto-reveal broadcasting is a
            // separate concern (see the wallet's own sync-loop wiring),
            // this just makes sure it's visibly flagged if that hasn't
            // happened yet for some reason.
            pendingAlerts.add(new Alert(AlertType.REVEAL_DEADLINE_APPROACHING, state.name, state.nameHash,
                    "Reveal deadline for \"" + state.name + "\" is in " + blocksUntilDeadline
                            + " blocks -- this bid's entire lockup is permanently forfeited if it isn't revealed in time."));
        }
    }

    public AuctionState getAuctionState(byte[] nameHash) {
        return watchedAuctions.get(hex(nameHash));
    }

    /** A snapshot of every currently-watched auction's state -- used by
     *  WalletAuctionEngine to determine what needs fetching and
     *  checking each sync pass, without exposing the live, mutable map
     *  itself. */
    public java.util.List<AuctionState> watchedAuctionsSnapshot() {
        return new ArrayList<>(watchedAuctions.values());
    }

    /** Returns and clears every alert generated since the last call --
     *  a simple pollable queue rather than a push-based listener, since
     *  there's no GUI yet to push to. */
    public List<Alert> drainAlerts() {
        List<Alert> result = new ArrayList<>(pendingAlerts);
        pendingAlerts.clear();
        return result;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}