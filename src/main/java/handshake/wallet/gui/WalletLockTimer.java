package handshake.wallet.gui;

import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fires a callback after a configurable period of complete user
 * inactivity -- no mouse or keyboard activity ANYWHERE in this
 * application, not just within one particular component. Deliberately
 * a GLOBAL listener (Toolkit.addAWTEventListener), not one attached to
 * a specific component: activity in any part of the window (clicking a
 * button, typing in a field, scrolling) should reset the clock, and a
 * listener on one specific component would miss everything happening
 * elsewhere in the window.
 * <p>
 * Locking here only ever means gating the UI -- see WalletMainFrame's
 * own onAutoLock(): the underlying WalletSession and its WalletSyncLoop
 * are never touched by this class at all, deliberately. Syncing
 * (updating balance/name/auction state from newly-scanned blocks) and
 * rebroadcasting an already-signed pending transaction both only need
 * already-derived, public information (watched address hashes, the
 * header chain) -- neither needs the decrypted seed or account key,
 * which only actually get used at the moment a NEW transaction is
 * signed. So there's nothing about "locked" that background work
 * needs to know about or be paused for; it keeps running exactly as it
 * did before the lock fired.
 * <p>
 * DEVELOPMENT_TIMEOUT_MINUTES is deliberately long (600 minutes) so
 * this never gets in the way during active development.
 * PRODUCTION_TIMEOUT_MINUTES (15) is the real, intended value for an
 * actual release. Which applies is controlled by the single
 * ACTIVE_MODE constant below -- a deliberate, explicit change to make
 * at release time, not a runtime setting, matching how this project
 * already handles similar build-time-vs-runtime distinctions elsewhere
 * (e.g. the node's own VERBOSE_WIRE_LOGGING flag).
 */
public class WalletLockTimer {

    private enum Mode { DEVELOPMENT, PRODUCTION }

    // ── The one line to change before a real release ────────────────
    private static final Mode ACTIVE_MODE = Mode.DEVELOPMENT;
    // ──────────────────────────────────────────────────────────────

    private static final long DEVELOPMENT_TIMEOUT_MINUTES = 600;
    private static final long PRODUCTION_TIMEOUT_MINUTES = 15;

    private static long timeoutMillis() {
        long minutes = ACTIVE_MODE == Mode.DEVELOPMENT ? DEVELOPMENT_TIMEOUT_MINUTES : PRODUCTION_TIMEOUT_MINUTES;
        return minutes * 60_000L;
    }

    private final Runnable onLock;
    private final AtomicLong lastActivityMillis = new AtomicLong();
    private final AWTEventListener activityListener = e -> lastActivityMillis.set(System.currentTimeMillis());

    // A javax.swing.Timer (fires on the EDT), not a background thread
    // or scheduler -- this only ever compares two longs and maybe
    // invokes onLock, nothing here does real work or blocks, so unlike
    // WalletSyncLoop's actual network I/O there's no reason to keep
    // this off the EDT.
    private final Timer checkTimer;

    private volatile boolean running = false;

    public WalletLockTimer(Runnable onLock) {
        this.onLock = onLock;
        checkTimer = new Timer(60_000, e -> checkForInactivity());
        checkTimer.setRepeats(true);
    }

    /** Begins tracking activity and checking for the configured
     *  timeout. Resets the clock to "now" -- always call this right
     *  when a wallet becomes genuinely visible/interactive (first
     *  unlock, or a successful re-verify after this same timer's own
     *  onLock already fired once), not just once at application
     *  startup. */
    public void start() {
        if (running) return;
        running = true;
        lastActivityMillis.set(System.currentTimeMillis());
        Toolkit.getDefaultToolkit().addAWTEventListener(activityListener,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        checkTimer.start();
    }

    /** Stops tracking entirely -- called both by this class itself the
     *  moment onLock actually fires (no reason to keep checking for
     *  MORE inactivity while already locked) and by WalletMainFrame
     *  when no wallet is loaded at all (the picker screen, mid-wizard)
     *  or the window is closing. */
    public void stop() {
        if (!running) return;
        running = false;
        Toolkit.getDefaultToolkit().removeAWTEventListener(activityListener);
        checkTimer.stop();
    }

    private void checkForInactivity() {
        long idleMillis = System.currentTimeMillis() - lastActivityMillis.get();
        if (idleMillis >= timeoutMillis()) {
            stop();
            onLock.run();
        }
    }
}