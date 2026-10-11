package handshake.wallet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Watches every name the wallet currently owns for an approaching
 * renewal deadline, and can auto-build the RENEW transaction to handle
 * it -- the same alert-plus-automatic-action pattern already built for
 * BID reveal deadlines (see WalletAuctionEngine), applied to a
 * different, much longer-fused but equally real risk: a name that goes
 * too long without a RENEW becomes "expired" and available for anyone
 * to OPEN again, per real hsd's own isExpired() rule (already verified
 * and reused on the node side in BlockProcessor/CovenantValidator):
 * RENEWAL_WINDOW = 2 * 365 * 144 = 105,120 blocks, roughly two years.
 *
 * Deliberately less aggressive than auto-reveal in one specific way:
 * reveal has no reasonable case for a person wanting to skip it (see
 * WalletBidManager's own comment on that), so auto-reveal is
 * unconditional. Losing a name to non-renewal, by contrast, is
 * something a person might genuinely want to happen -- they no longer
 * want the name, don't want to keep paying the small renewal fee
 * forever. So this class raises an alert well ahead of the deadline
 * (30 days -- 4,320 blocks -- comfortable lead time given the total
 * window is measured in years, not days) and CAN build the RENEW
 * transaction on request, but doesn't broadcast anything unprompted --
 * whether to actually auto-renew is a real policy choice left to
 * whatever calls this (eventually a per-name Settings toggle in the
 * GUI), not decided here.
 */
public class WalletNameMonitor {

    public static final int RENEWAL_WINDOW = 2 * 365 * 144; // 105,120 blocks, ~2 years -- confirmed, same constant already verified and used on the node side
    private static final int RENEWAL_ALERT_THRESHOLD = 30 * 144; // 4,320 blocks, ~30 days

    private final WalletNameState nameState;
    private final Set<String> alertedThisWindow = new HashSet<>();

    public WalletNameMonitor(WalletNameState nameState) {
        this.nameState = nameState;
    }

    /** Checks every currently-owned name against the current height,
     *  returning a RENEWAL_DEADLINE_APPROACHING alert for any whose
     *  deadline is inside the alert threshold and hasn't already been
     *  alerted this window (reset once the name is actually renewed,
     *  via clearAlert()), or a LOST_AUCTION-style alert (reusing that
     *  same alert type -- the underlying situation is the same shape:
     *  a real, permanent, missed-deadline loss) for one that's already
     *  expired outright. */
    public List<WalletAuctionMonitor.Alert> checkRenewalDeadlines(int currentHeight) {
        List<WalletAuctionMonitor.Alert> alerts = new ArrayList<>();
        for (WalletNameState.Entry entry : nameState.allOwnedNames()) {
            int deadline = entry.renewalHeight + RENEWAL_WINDOW;
            int blocksRemaining = deadline - currentHeight;
            String key = hex(entry.nameHash);

            if (blocksRemaining <= 0) {
                alerts.add(new WalletAuctionMonitor.Alert(
                        WalletAuctionMonitor.AlertType.LOST_AUCTION, entry.name, entry.nameHash,
                        "\"" + entry.name + "\" was not renewed in time and has expired -- "
                                + "it is no longer owned and is available for anyone to open again."));
                alertedThisWindow.remove(key);
            } else if (blocksRemaining <= RENEWAL_ALERT_THRESHOLD && !alertedThisWindow.contains(key)) {
                alertedThisWindow.add(key);
                alerts.add(new WalletAuctionMonitor.Alert(
                        WalletAuctionMonitor.AlertType.REVEAL_DEADLINE_APPROACHING, entry.name, entry.nameHash,
                        "\"" + entry.name + "\" needs to be renewed within " + blocksRemaining
                                + " blocks (~" + (blocksRemaining / 144) + " days) or it will expire and be lost."));
            }
        }
        return alerts;
    }

    /** Clears the alert-already-fired flag for a name -- call once a
     *  RENEW for it has actually been confirmed, so a future renewal
     *  cycle (the name aging back toward its next 2-year deadline) can
     *  alert again when it's genuinely due, rather than staying
     *  permanently silenced by the first alert ever fired for it. */
    public void clearAlert(byte[] nameHash) {
        alertedThisWindow.remove(hex(nameHash));
    }

    /** Builds (but does not broadcast) a RENEW transaction for a given
     *  owned name, using a header from comfortably within real hsd's
     *  own "recent block" requirement for RENEW's own commitment field
     *  (see WalletCovenantTxBuilder.buildRenew()'s own comment) --
     *  100 blocks back from the current tip, deliberately conservative
     *  and nowhere near either boundary of that real window. */
    public WalletCovenantTxBuilder.BuildResult buildAutoRenew(WalletNameState.Entry entry, WalletHeaderChain headerChain,
                                                              WalletUtxoSet utxoSet, WalletAddressManager addrMgr,
                                                              HDKey accountKey, long feeRatePerByte) {
        int tip = headerChain.tipHeight();
        int recentHeight = Math.max(0, tip - 100);
        byte[] recentHeader = headerChain.headerAt(recentHeight);
        byte[] recentBlockHash = HeaderUtil.hash(recentHeader);

        return WalletCovenantTxBuilder.buildRenew(utxoSet, addrMgr, accountKey, entry.nameHash, entry.openHeight,
                entry.ownerTxid, entry.ownerOutputIndex, recentBlockHash, feeRatePerByte);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}