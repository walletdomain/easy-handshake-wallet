package handshake.wallet.gui;

import handshake.wallet.WalletNetworkSync;
import handshake.wallet.WalletSession;
import handshake.wallet.WalletStorage;

import javax.swing.SwingUtilities;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Drives WalletNetworkSync.syncOnce() periodically for one unlocked
 * wallet session, off the Swing Event Dispatch Thread -- see
 * WalletMainFrame's own top comment for why that matters (blocking the
 * EDT with real network I/O freezes the entire window). Mirrors the
 * node project's own ChainSync scheduler exactly: a single-threaded,
 * daemon-backed ScheduledExecutorService, not a javax.swing.Timer or a
 * fresh SwingWorker per cycle -- the same well-tested pattern this
 * whole project already relies on elsewhere for "periodic background
 * work, one thread, never blocks the caller."
 * <p>
 * Calls WalletSession.saveState() after every successful sync --
 * deliberately NOT the full save(), which also needs the passphrase to
 * re-encrypt pending bids. saveState() alone needs no passphrase at
 * all (nothing it persists is encrypted -- see its own comment), so
 * this gives real, continuous, automatic persistence without ever
 * needing this class -- or WalletMainFrame, which still doesn't retain
 * the passphrase after unlock -- to hold a credential just for routine
 * saving. Pending bids specifically stay unsaved by this loop; see
 * WalletSession.savePendingBids()'s own comment for where that
 * actually belongs (right after placing a bid, a moment that already
 * has the passphrase in hand from signing it).
 */
public class WalletSyncLoop {

    private static final long SYNC_INTERVAL_SECONDS = 30;
    private static final long FEE_RATE_PER_BYTE = 100; // dollarydoos/byte -- a placeholder default, same as any real wallet's fee estimation would need its own dedicated logic to replace, not something to invent here as a side effect of the sync loop itself

    private final WalletStorage storage;
    private final WalletSession session;
    private final Consumer<WalletSession.SyncResult> onSyncComplete; // always invoked on the EDT
    private final Consumer<Exception> onSyncError;                   // always invoked on the EDT

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wallet-sync");
                t.setDaemon(true);
                return t;
            });

    private final Consumer<String> onProgress; // always invoked on the EDT; may be null

    public WalletSyncLoop(WalletStorage storage, WalletSession session,
                          Consumer<WalletSession.SyncResult> onSyncComplete,
                          Consumer<Exception> onSyncError) {
        this(storage, session, onSyncComplete, onSyncError, null);
    }

    public WalletSyncLoop(WalletStorage storage, WalletSession session,
                          Consumer<WalletSession.SyncResult> onSyncComplete,
                          Consumer<Exception> onSyncError,
                          Consumer<String> onProgress) {
        this.storage = storage;
        this.session = session;
        this.onSyncComplete = onSyncComplete;
        this.onSyncError = onSyncError;
        this.onProgress = onProgress;
    }

    /** Starts periodic syncing, first attempt immediately (not after
     *  waiting a full interval) -- matching ChainSync's own
     *  scheduleWithFixedDelay(..., 0, ...) on the node side: a person
     *  who just unlocked a wallet wants to see current data as soon as
     *  possible, not wait 30 seconds for the first refresh. */
    public void start() {
        scheduleNext(0);
    }

    /** Runs the next pass after delaySeconds. Each pass decides its own
     *  successor: right away (0s) while a pass is still delivering new
     *  headers -- so catching up on a long chain isn't throttled to one
     *  batch per 30 seconds -- otherwise after the normal interval. */
    private void scheduleNext(long delaySeconds) {
        try {
            scheduler.schedule(() -> scheduleNext(syncOnceSafely()), delaySeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            // stop() was called -- nothing more to schedule.
        }
    }

    /**
     * Wrapped in try/catch(Throwable), not just Exception -- a real,
     * well-known ScheduledExecutorService gotcha: an uncaught throwable
     * from a scheduled task silently cancels every future execution of
     * that same task, with no exception surfaced anywhere a person
     * would ever see it. Without this, one bad sync attempt wouldn't
     * just fail once -- it would silently end all syncing for the rest
     * of the session, with the dashboard just quietly going stale and
     * no indication why. saveState()'s own, much rarer failure mode
     * (a genuine storage-layer error) is caught by this exact same
     * guard, for the same reason -- a save failure is still a real
     * error worth surfacing via onSyncError, not something that should
     * silently end future syncing either.
     */
    private long syncOnceSafely() {
        try {
            WalletSession.SyncResult result = WalletNetworkSync.syncOnce(storage, session, FEE_RATE_PER_BYTE,
                    onProgress == null ? null : text -> SwingUtilities.invokeLater(() -> onProgress.accept(text)));
            session.saveState(storage);
            if (!result.moreToScan) handshake.wallet.WalletDiagnostics.write(session); // wallet-debug.txt, for checking the balance
            SwingUtilities.invokeLater(() -> onSyncComplete.accept(result));
            // Still receiving headers => more to fetch: go again immediately.
            return (result.headersReceived > 0 || (result.moreToScan && result.blocksScannedForBalance > 0))
                    ? 0 : SYNC_INTERVAL_SECONDS;
        } catch (Throwable t) {
            Exception e = (t instanceof Exception ex) ? ex : new RuntimeException(t);
            SwingUtilities.invokeLater(() -> onSyncError.accept(e));
            return SYNC_INTERVAL_SECONDS;
        }
    }

    /** Stops future sync attempts. Does not interrupt one already in
     *  progress -- WalletNetworkSync.syncOnce() closes its own
     *  connections in a finally block regardless of how it exits, so
     *  letting an in-flight attempt finish naturally is simpler and
     *  just as safe as trying to cut it off mid-flight. */
    public void stop() {
        scheduler.shutdown();
    }
}