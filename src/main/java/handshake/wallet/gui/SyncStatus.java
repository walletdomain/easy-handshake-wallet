package handshake.wallet.gui;

import handshake.wallet.WalletSession;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One snapshot of "how synchronized is this wallet", shown in two places:
 * the sidebar's sync panel (label + progress bar + detail) and a short text
 * in the top bar. Built from the raw progress strings WalletSession /
 * WalletNetworkSync report while a pass is running, and from the finished
 * pass's result. All the wording and the percentage maths live here so the
 * two displays can never disagree.
 *
 * Overall progress: headers are 10% of the bar, block scanning the other
 * 90%. DONE is 100% (the bar turns green); ERROR keeps whatever fraction
 * was last known.
 */
public record SyncStatus(Kind kind, String label, String detail, String topText,
                         double fraction, int remaining) {

    public enum Kind { IDLE, WORKING, DONE, ERROR }

    private static final double HEADER_WEIGHT = 0.10;
    /** When already synced, ignore progress reports for gaps smaller than this
     *  (the routine 30-second check for a block or two must not flicker). */
    public static final int QUIET_GAP = 50;

    private static final Pattern HEADERS = Pattern.compile("Downloading block headers (\\d+)(?: / (\\d+))?");
    private static final Pattern SCANNING = Pattern.compile("Scanning blocks (\\d+) / (\\d+)");

    public static SyncStatus idle() {
        return new SyncStatus(Kind.IDLE, "Not connected", " ", "Not connected", 0, Integer.MAX_VALUE);
    }

    public static SyncStatus connecting() {
        return new SyncStatus(Kind.WORKING, "Connecting...", " ", "Connecting...", -1, Integer.MAX_VALUE);
    }

    public static SyncStatus error(String message, double lastFraction) {
        return new SyncStatus(Kind.ERROR, "Offline, retrying", " ", "Sync error", Math.max(lastFraction, 0), Integer.MAX_VALUE);
    }

    /** Maps a raw progress string; returns null if it isn't one we recognise. */
    public static SyncStatus fromProgress(String text) {
        if (text == null) return null;
        Matcher m = SCANNING.matcher(text);
        if (m.find()) {
            long h = Long.parseLong(m.group(1)), tip = Long.parseLong(m.group(2));
            double f = HEADER_WEIGHT + (1 - HEADER_WEIGHT) * Math.max(0, h - 1) / Math.max(1, tip);
            return working("Block scanning", f, fmt(h) + " / " + fmt(tip), (int) Math.min(Integer.MAX_VALUE, tip - h));
        }
        m = HEADERS.matcher(text);
        if (m.find()) {
            long n = Long.parseLong(m.group(1));
            if (m.group(2) == null) {
                return working("Headers", HEADER_WEIGHT / 2, fmt(n), Integer.MAX_VALUE);
            }
            long peer = Long.parseLong(m.group(2));
            double f = HEADER_WEIGHT * Math.min(1.0, (double) n / Math.max(1, peer));
            SyncStatus w = working("Headers", f, fmt(n) + " / " + fmt(peer), (int) Math.min(Integer.MAX_VALUE, Math.max(0, peer - n)));
            // The label shows this phase's own progress; the top text keeps the overall figure.
            int phasePct = (int) Math.min(99, 100.0 * n / Math.max(1, peer));
            return new SyncStatus(w.kind(), "Syncing headers: " + phasePct + "%", w.detail(), w.topText(), w.fraction(), w.remaining());
        }
        if (text.startsWith("Connecting")) {
            return new SyncStatus(Kind.WORKING, "Connecting...", " ", "Connecting...", -1, Integer.MAX_VALUE);
        }
        return null;
    }

    /** The state after a finished pass. */
    public static SyncStatus fromResult(WalletSession session, WalletSession.SyncResult result) {
        int tip = session.headerChain.tipHeight();
        if (result.moreToScan) {
            int scanned = session.scannedHeight();
            double f = HEADER_WEIGHT + (1 - HEADER_WEIGHT) * (double) scanned / Math.max(1, tip);
            return working("Block scanning", f, fmt(scanned) + " / " + fmt(tip), Math.max(0, tip - scanned));
        }
        return new SyncStatus(Kind.DONE, "Synced", "Block " + fmt(tip), "Synced · " + fmt(tip), 1.0, 0);
    }

    private static SyncStatus working(String phase, double fraction, String detail, int remaining) {
        int pct = (int) Math.min(99, Math.floor(fraction * 100));
        return new SyncStatus(Kind.WORKING, phase + ": " + pct + "%", detail, "Wallet Syncing: " + pct + "%", fraction, remaining);
    }

    private static String fmt(long n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }
}