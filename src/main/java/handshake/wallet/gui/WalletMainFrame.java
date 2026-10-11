package handshake.wallet.gui;

import handshake.wallet.WalletSession;
import handshake.wallet.WalletStorage;
import handshake.wallet.gui.panels.CreateWalletWizardPanel;
import handshake.wallet.gui.panels.AuctionsPanel;
import handshake.wallet.gui.panels.DashboardPanel;
import handshake.wallet.gui.panels.HistoryPanel;
import handshake.wallet.gui.panels.NamesPanel;
import handshake.wallet.gui.panels.RestoreWalletWizardPanel;
import handshake.wallet.gui.panels.UnlockWalletPanel;
import handshake.wallet.gui.panels.WalletPickerScreen;
import handshake.wallet.gui.theme.EasyHandshakeWalletTheme;
import handshake.wallet.gui.theme.WalletFonts;
import handshake.wallet.gui.theme.WalletPickerDropdown;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.sql.SQLException;
import java.util.List;

/**
 * The wallet's main application window. Two fixed regions:
 *
 *  - TopBar (NORTH): the app name always, and (once a wallet has
 *    genuinely been unlocked this session) the wallet picker too --
 *    see relocateWalletPickerToTopBar()'s own comment for why it
 *    isn't there from the start.
 *  - A swappable bottom region (CENTER), toggling between:
 *      PICKER          -- the very first thing shown: "Wallet:" label
 *                          and picker dropdown, centered, before
 *                          anything has been unlocked this session.
 *      CREATE_WALLET   -- the real create-a-new-wallet wizard.
 *      RESTORE_WALLET  -- the real restore-a-previous-wallet wizard.
 *      UNLOCK_WALLET   -- the real "enter your password" prompt.
 *      WALLET_LOADED   -- the familiar sidebar + main content pair,
 *                          once a real wallet is actually loaded.
 *      WALLET_LOCKED   -- shown after WalletLockTimer's configured
 *                          period of inactivity; re-verifies the same
 *                          password without rebuilding the session
 *                          (see onAutoLock()). Gates the UI only --
 *                          activeSession and activeSyncLoop are never
 *                          touched, so background sync (and, later,
 *                          rebroadcasting an already-signed pending
 *                          transaction) keeps running exactly as
 *                          before, unaffected by lock state, since
 *                          neither needs the decrypted seed at all.
 *
 * RE-ARCHITECTURE: this class now owns a single, live "Wallet:" label
 * and WalletPickerDropdown instance for its ENTIRE lifetime -- not two
 * separate dropdowns (one for the initial picker screen, one for
 * TopBar) kept in sync, which would be two different components a
 * person could theoretically see out of sync with each other, and two
 * places for listener wiring to drift apart. Instead, the exact same
 * live components start out laid inside WalletPickerScreen (centered,
 * the pre-unlock experience), then physically relocate into TopBar the
 * first time a wallet is genuinely unlocked -- see
 * relocateWalletPickerToTopBar()'s own comment. Picking a wallet from
 * the centered screen and picking one from TopBar later are the exact
 * same action underneath, driven by the exact same listener on the
 * exact same component; only where it's currently mounted differs.
 * This same dropdown stays live even while WALLET_LOCKED is showing
 * (it's in TopBar, outside the swappable bottom region), which is
 * exactly why walletIsLocked exists -- see its own comment for the
 * lock-bypass path that would otherwise open.
 *
 * Network work (WalletSession.syncOnce(), broadcasting transactions)
 * never runs on this class's own thread -- this is the Swing Event
 * Dispatch Thread, and blocking it would freeze the entire window.
 * WalletSyncLoop (started in onWalletUnlocked(), stopped only when
 * switching wallets or the window closing -- never by locking) is the
 * real answer to that; SwingWorker-driven user actions for one-off
 * work (e.g. an actual "send" button) remain a later, separate task.
 */
public class WalletMainFrame extends JFrame {

    private static final String STATE_PICKER = "PICKER";
    private static final String STATE_CREATE_WALLET = "CREATE_WALLET";
    private static final String STATE_RESTORE_WALLET = "RESTORE_WALLET";
    private static final String STATE_UNLOCK_WALLET = "UNLOCK_WALLET";
    private static final String STATE_WALLET_LOADED = "WALLET_LOADED";
    private static final String STATE_WALLET_LOCKED = "WALLET_LOCKED";

    private final CardLayout bottomCardLayout = new CardLayout();
    private final JPanel bottomRegion = new JPanel(bottomCardLayout);

    private final CardLayout contentCardLayout = new CardLayout();
    private final JPanel contentArea = new JPanel(contentCardLayout);

    private final WalletStorage storage;
    private final TopBar topBar;

    // The single, live wallet-picker components this whole class shares
    // between the initial, centered picker screen and TopBar -- see
    // this class's own comment for the full reasoning on why one
    // reparented instance, not two kept in sync.
    private final JLabel walletLabel;
    private final WalletPickerDropdown dropdown;

    // Set exactly once, the first time a wallet is genuinely unlocked
    // -- guards relocateWalletPickerToTopBar() so it only ever moves
    // the picker components once, not on every subsequent unlock.
    private boolean walletPickerRelocated = false;

    // The last real wallet selection made, if any -- so Cancelling out
    // of "Create a new wallet", "Restore a previous wallet", or an
    // unlock attempt can revert to whatever was actually loaded before,
    // not just a generic placeholder. Stays null through a true first
    // run, since nothing real has ever been selected yet in that case.
    //
    // Set ONLY once a wallet is genuinely unlocked (see
    // onWalletUnlocked() below), not merely picked from the dropdown --
    // deliberately: picking a locked wallet and then cancelling its own
    // unlock prompt needs to revert to whatever was ACTUALLY loaded
    // before (a different, already-unlocked wallet, or the initial
    // picker screen), never back to the same still-locked wallet that
    // was just cancelled out of, which would just show the same prompt
    // again.
    private WalletPickerDropdown.Entry lastRealSelection;

    // The currently unlocked wallet's full in-memory state, if any --
    // set only once WalletSession.open() has actually succeeded (a real
    // password verification plus loading address/UTXO/bid/name state
    // and the header chain from storage, not just a UI selection). Null
    // whenever nothing is genuinely unlocked yet, including right after
    // picking a wallet from the dropdown but before its password has
    // been entered.
    //
    // Not yet consumed by DashboardPanel or the other content screens
    // -- see DashboardPanel's own comment on why that wiring is a
    // separate, later task. This field's own job right now is narrower
    // and already real on its own: knowing definitively whether the
    // wallet currently showing has actually been unlocked, so
    // re-selecting it (e.g. reverting here after cancelling a
    // different wallet's own unlock attempt) can skip re-prompting for
    // a password already verified this session, rather than asking
    // again for no reason.
    private WalletSession activeSession;

    // Stored as a field (not a local variable inside
    // buildWalletLoadedPane()) specifically so onWalletUnlocked() below
    // can reach back into the same, already-built instance and refresh
    // it -- there's exactly one DashboardPanel for this window's whole
    // lifetime, built once before any wallet exists, refreshed
    // thereafter, never rebuilt from scratch.
    private final DashboardPanel dashboardPanel = new DashboardPanel();
    private final AuctionsPanel auctionsPanel = new AuctionsPanel();
    private final NamesPanel namesPanel = new NamesPanel();
    private final HistoryPanel historyPanel = new HistoryPanel();

    // The sync loop for whichever wallet is currently active, if any --
    // exactly one at a time. See onWalletUnlocked() for why an old one
    // must always be stopped before a new one starts: two loops running
    // concurrently would each be syncing (and refreshing the SAME
    // shared dashboardPanel from) two different wallets' state at once.
    private WalletSyncLoop activeSyncLoop;

    // Kept as a field so sync progress can be shown in the sidebar's
    // status line (bottom left) as well as the top bar -- it used to be a
    // local variable, so that label stayed on "Not connected" forever.
    private Sidebar sidebar;

    private SyncStatus currentSync = SyncStatus.idle();

    /** Shows the same sync status in both places a person looks for it:
     *  the sidebar's sync panel and a short text in the top bar. */
    private void showSync(SyncStatus status, String tooltip) {
        currentSync = status;
        sidebar.setSync(status);
        topBar.setSyncStatus(status.topText(), status.kind() == SyncStatus.Kind.ERROR, tooltip);
    }

    /** A progress report from a running pass. Once synced, reports for a
     *  gap of only a few blocks (the routine 30-second check) are ignored
     *  so the green "Synced" state does not flicker. */
    private void onSyncProgress(String text) {
        SyncStatus s = SyncStatus.fromProgress(text);
        if (s == null) return;
        if (currentSync.kind() == SyncStatus.Kind.DONE && s.remaining() < SyncStatus.QUIET_GAP) return;
        showSync(s, null);
    }

    // Gates the UI ONLY -- see WalletLockTimer's own comment for the
    // full reasoning on why this never touches activeSession or
    // activeSyncLoop at all. Not started until a wallet is genuinely
    // loaded (onWalletUnlocked()), and stopped whenever one isn't (the
    // picker screen, mid-wizard, window close) -- no point tracking
    // toward an auto-lock that has nothing to lock.
    private final WalletLockTimer lockTimer = new WalletLockTimer(this::onAutoLock);

    // Set immediately before onWalletCreated() below refreshes the
    // dropdown, cleared right after use. Exists for one specific,
    // narrow reason: WalletPickerDropdown.refresh() calls
    // setSelectedItem() internally when told to select the just-
    // created wallet, which fires this SAME class's own selection
    // listener -- meaning, without this guard, a brand-new wallet
    // (whose password was just typed, seconds ago, to create it)
    // would immediately trigger its own unlock prompt right after
    // being created, for no real reason. Not needed for the ordinary
    // "cancel an unlock attempt, revert to what was already loaded"
    // path -- lastRealSelection/activeSession already handle that
    // correctly on their own; this covers only the one moment a
    // wallet's own creation flow itself changes the dropdown's
    // selection programmatically.
    private boolean skipNextUnlockPrompt = false;

    public WalletMainFrame(WalletStorage storage) throws SQLException {
        super("Easy Handshake Wallet");
        this.storage = storage;
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        // Fires before EXIT_ON_CLOSE's own internal handling actually
        // exits the JVM (standard Swing ordering: registered
        // WindowListeners always run first) -- gives the active sync
        // loop, if any, a chance to stop cleanly rather than just being
        // killed mid-cycle by the JVM tearing down. Its own scheduler
        // thread is a daemon (see WalletSyncLoop), so this isn't
        // needed to let the JVM exit at all -- just to not leave an
        // in-flight sync attempt abruptly severed for no reason.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (activeSyncLoop != null) activeSyncLoop.stop();
                lockTimer.stop();
            }
        });
        setSize(1100, 700);
        setLocationRelativeTo(null);

        // Hides just the visible title bar text -- TopBar's own brand
        // label already shows the app name prominently, so repeating it
        // in plain system font right above it would be pure
        // duplication. Uses FlatLaf's own documented client property
        // rather than blanking the JFrame's title string outright:
        // getTitle() still returns "Easy Handshake Wallet", so the
        // taskbar entry and Alt-Tab switcher are unaffected -- only the
        // title bar's own text rendering is suppressed.
        getRootPane().putClientProperty("JRootPane.titleBarShowTitle", false);

        List<WalletStorage.WalletInfo> wallets = storage.listWallets();

        walletLabel = new JLabel("Wallet:");
        walletLabel.setFont(WalletFonts.body(16f));
        walletLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));

        dropdown = new WalletPickerDropdown(wallets);
        // Attached AFTER construction, deliberately -- the constructor
        // above already defaults to selecting index 0 internally (see
        // WalletPickerDropdown's own constructor), which would fire an
        // ActionEvent immediately if a listener were already attached,
        // triggering an unlock prompt before this class has even
        // finished building itself. Attaching the listener only once
        // that default, initial selection has already happened means
        // this fires only on a REAL, later selection -- an active
        // choice, the same way any ordinary dropdown behaves.
        dropdown.addActionListener(e -> onWalletSelectionChanged());

        topBar = new TopBar();

        bottomRegion.add(new WalletPickerScreen(walletLabel, dropdown), STATE_PICKER);
        bottomRegion.add(buildWalletLoadedPane(), STATE_WALLET_LOADED);

        setLayout(new BorderLayout());
        add(topBar, BorderLayout.NORTH);
        add(bottomRegion, BorderLayout.CENTER);

        // Always starts here now, regardless of whether any wallets
        // already exist -- the picker screen's own dropdown already
        // includes "Create a new wallet"/"Restore a previous wallet"
        // alongside any real wallets, so it's the right starting point
        // either way. Deliberately does NOT auto-select or auto-unlock
        // even when there's only one existing wallet -- the person
        // makes an explicit choice here, the same way they would from
        // TopBar's dropdown later.
        bottomCardLayout.show(bottomRegion, STATE_PICKER);
    }

    private JPanel buildWalletLoadedPane() {
        sidebar = new Sidebar(this::navigateTo);

        contentArea.add(dashboardPanel, "Dashboard");
        contentArea.add(placeholderPanel("Send"), "Send");
        contentArea.add(placeholderPanel("Receive"), "Receive");
        contentArea.add(namesPanel, "Names");
        contentArea.add(auctionsPanel, "Auctions");
        contentArea.add(historyPanel, "History");
        contentArea.add(placeholderPanel("Settings"), "Settings");
        contentCardLayout.show(contentArea, "Dashboard");

        JPanel pane = new JPanel(new BorderLayout());
        pane.add(sidebar, BorderLayout.WEST);
        pane.add(contentArea, BorderLayout.CENTER);
        return pane;
    }

    /** Reacts to a wallet-picker selection -- whether it came from the
     *  initial, centered picker screen or (after relocation) from
     *  TopBar's own dropdown; this method doesn't know or care which,
     *  since it's the exact same component either way. */
    // True exactly while STATE_WALLET_LOCKED is showing for
    // activeSession's own wallet -- set in onAutoLock(), cleared in
    // onWalletUnlocked()/onReVerified(). Exists specifically to close
    // a second lock-bypass path distinct from the re-verify panel's
    // own Cancel button (see onAutoLock()'s comment on that one): the
    // wallet-picker dropdown stays live in TopBar even while locked
    // (it's outside the swappable bottomRegion), so re-selecting the
    // SAME already-active wallet from it would otherwise hit
    // onWalletSelectionChanged()'s "already the currently-unlocked
    // wallet" branch below and show STATE_WALLET_LOADED directly, with
    // no password required at all.
    private boolean walletIsLocked = false;

    private void onWalletSelectionChanged() {
        WalletPickerDropdown.Entry selected = (WalletPickerDropdown.Entry) dropdown.getSelectedItem();
        if (selected == null) return;

        switch (selected.kind) {
            case WALLET -> {
                if (skipNextUnlockPrompt) {
                    // See this field's own comment -- this selection
                    // change is a side effect of onWalletCreated()'s
                    // own dropdown refresh, not a real user action, so
                    // skip straight to the loaded pane without
                    // re-prompting for a password just typed to create
                    // this exact wallet.
                    skipNextUnlockPrompt = false;
                    lastRealSelection = selected;
                    relocateWalletPickerToTopBar();
                    bottomCardLayout.show(bottomRegion, STATE_WALLET_LOADED);
                } else if (activeSession != null && activeSession.walletName.equals(selected.wallet.name) && walletIsLocked) {
                    // The currently-active wallet, but LOCKED -- see
                    // walletIsLocked's own comment for why this can't
                    // just fall into the branch below. Re-showing the
                    // same lock screen (not calling onWalletUnlocked()
                    // or touching activeSyncLoop) keeps this exactly
                    // equivalent to the re-verify panel's own Cancel
                    // button: a real password is still required.
                    onAutoLock();
                } else if (activeSession != null && activeSession.walletName.equals(selected.wallet.name)) {
                    // Already the currently-unlocked wallet -- e.g.
                    // reverting here after cancelling a DIFFERENT
                    // wallet's own unlock attempt (see
                    // onWizardCancelled()). Just show what's already
                    // genuinely loaded; re-prompting for a password
                    // already verified this session would be asking
                    // for no reason.
                    bottomCardLayout.show(bottomRegion, STATE_WALLET_LOADED);
                } else {
                    showUnlockPrompt(selected);
                }
            }
            case ACTION -> {
                if (selected.displayText.equals(WalletPickerDropdown.ACTION_CREATE)) {
                    showCreateWalletWizard();
                } else {
                    showRestoreWalletWizard();
                }
            }
            case DIVIDER -> { /* never actually selectable -- see WalletPickerDropdown.setSelectedItem() */ }
        }
    }

    private void showCreateWalletWizard() {
        // Built fresh each time, not reused across visits -- a
        // cancelled attempt shouldn't leave a half-filled form (a
        // stale name, an already-generated seed phrase) sitting around
        // for the next time someone picks "Create a new wallet" again.
        CreateWalletWizardPanel wizard = new CreateWalletWizardPanel(storage, this::onWalletCreated, this::onWizardCancelled);
        bottomRegion.add(wizard, STATE_CREATE_WALLET);
        bottomCardLayout.show(bottomRegion, STATE_CREATE_WALLET);
    }

    private void showRestoreWalletWizard() {
        // Same "built fresh each time" reasoning as showCreateWalletWizard()
        // above -- a cancelled restore attempt shouldn't leave a
        // half-entered recovery phrase sitting around either.
        RestoreWalletWizardPanel wizard = new RestoreWalletWizardPanel(storage, this::onWalletCreated, this::onWizardCancelled);
        bottomRegion.add(wizard, STATE_RESTORE_WALLET);
        bottomCardLayout.show(bottomRegion, STATE_RESTORE_WALLET);
    }

    /** Shows the "enter your password" prompt for a specific wallet
     *  picked from the dropdown. Built fresh each time, same reasoning
     *  as the create/restore wizards above -- a cancelled or failed
     *  unlock attempt shouldn't leave a typed (even if wrong) password
     *  sitting in a field for whatever gets shown next. */
    private void showUnlockPrompt(WalletPickerDropdown.Entry selected) {
        UnlockWalletPanel unlockPanel = new UnlockWalletPanel(
                storage, selected.wallet, this::onWalletUnlocked, this::onWizardCancelled);
        bottomRegion.add(unlockPanel, STATE_UNLOCK_WALLET);
        bottomCardLayout.show(bottomRegion, STATE_UNLOCK_WALLET);
        // Queued rather than called directly -- the window is already
        // visible by the time this can be reached (the initial picker
        // screen is always shown first now, unlike the earlier design
        // where this could be reached directly from the constructor),
        // but queuing it is still the safer, simpler choice: it costs
        // nothing here and needs no special-casing if that ever
        // changes again later.
        SwingUtilities.invokeLater(unlockPanel::focusPasswordField);
    }

    /** Physically moves the shared "Wallet:" label and dropdown from
     *  wherever they're currently mounted (always the initial
     *  WalletPickerScreen, the first time this runs) into TopBar --
     *  called exactly once, the first time a wallet is genuinely
     *  unlocked or created/restored (see walletPickerRelocated's own
     *  comment). This is the actual mechanism behind this whole
     *  design: the picker starts centered in the main panel because
     *  nothing is loaded yet to justify a persistent top bar entry for
     *  it, and moves up into TopBar once something IS loaded, matching
     *  how switching between already-known wallets ought to work from
     *  then on -- a quick, persistent control, not a full-screen
     *  decision each time. */
    private void relocateWalletPickerToTopBar() {
        if (walletPickerRelocated) return;
        Container oldParent = dropdown.getParent();
        if (oldParent != null) {
            oldParent.remove(walletLabel);
            oldParent.remove(dropdown);
            oldParent.revalidate();
            oldParent.repaint();
        }
        topBar.installWalletPicker(walletLabel, dropdown);
        walletPickerRelocated = true;
    }

    /** Called once WalletSession.open() has actually succeeded -- a
     *  real, verified password plus every piece of this wallet's own
     *  persisted state loaded back into memory (see WalletSession's own
     *  comment). This is the point this whole prompt exists to reach:
     *  from here on, THIS wallet is genuinely unlocked, not just
     *  selected. */
    private void onWalletUnlocked(WalletSession session) {
        activeSession = session;
        lastRealSelection = (WalletPickerDropdown.Entry) dropdown.getSelectedItem();
        relocateWalletPickerToTopBar();
        bottomCardLayout.show(bottomRegion, STATE_WALLET_LOADED);
        // Real data now, not a mockup -- see DashboardPanel's own
        // comment on why refresh() (not a constructor parameter) is
        // the right shape: this panel was already built, empty, before
        // any session existed. Send/Receive/Names/Settings remain
        // separate, later tasks (see WalletMainFrame's own top
        // comment) -- this only wires the one screen actually built so
        // far.
        dashboardPanel.refresh(session, storage);
        auctionsPanel.refresh(session);
        namesPanel.refresh(session);
        historyPanel.refresh(session, storage);

        // Always stop whatever loop was running before starting a new
        // one -- see activeSyncLoop's own field comment for why two
        // can never run at once. Covers both switching to a genuinely
        // different wallet AND re-unlocking after this exact wallet
        // was, for whatever reason, unlocked a second time.
        if (activeSyncLoop != null) activeSyncLoop.stop();
        showSync(SyncStatus.connecting(), null);
        activeSyncLoop = new WalletSyncLoop(storage, session,
                result -> {
                    dashboardPanel.refresh(session, storage);
                    auctionsPanel.refresh(session);
                    namesPanel.refresh(session);
                    historyPanel.refresh(session, storage);
                    showSync(SyncStatus.fromResult(session, result), null);
                },
                // A NotEnoughPeersException here is a normal, expected
                // outcome sometimes (no network right now), not
                // necessarily a real problem -- shown the same way as
                // any other sync error rather than specially
                // downplayed, since a person watching this status line
                // over time genuinely benefits from knowing syncing
                // isn't currently succeeding, whatever the specific
                // cause.
                error -> showSync(SyncStatus.error(error.getMessage(), Math.max(0, currentSync.fraction())),
                        "Sync error: " + error.getMessage()),
                this::onSyncProgress);
        activeSyncLoop.start();

        // Starts (or restarts, after a prior auto-lock) the inactivity
        // clock fresh from this exact moment -- see WalletLockTimer's
        // own start() comment.
        walletIsLocked = false;
        lockTimer.start();
    }

    /**
     * WalletLockTimer's own callback, fired after the configured period
     * of complete inactivity. Calls activeSession.lock() -- destroying
     * its signing-capable key material in place (see WalletSession's
     * own lock() comment) -- but activeSyncLoop is never stopped, and
     * the session object itself is never discarded or replaced: every
     * OTHER piece of it (addressManager, utxoSet, headerChain, the
     * monitors) is completely unaffected, since none of it needs what
     * lock() just destroyed. See WalletLockTimer's own class comment
     * for the full reasoning: syncing and rebroadcasting an
     * already-signed pending transaction both only need already-public
     * information, never the decrypted seed, so there's nothing for
     * this to interrupt them for.
     * <p>
     * Built fresh each time, same reasoning as the create/restore
     * wizards and the first-unlock prompt -- a cancelled or failed
     * re-verify attempt shouldn't leave a typed (even wrong) password
     * sitting in a field for next time.
     */
    private void onAutoLock() {
        activeSession.lock();

        // onCancel deliberately wired back to onAutoLock itself, NOT
        // the shared onWizardCancelled() every other cancel button in
        // this class uses -- that method reverts by re-selecting
        // lastRealSelection, which onWalletSelectionChanged() then
        // shows as STATE_WALLET_LOADED directly whenever it already
        // matches activeSession's own wallet, with no re-verification
        // at all. Since lastRealSelection IS this exact locked wallet
        // (it can't be anything else -- it's only ever set on a real
        // unlock), that path would be a genuine lock bypass: click
        // Cancel, see the dashboard, no password needed. Routing back
        // here instead just re-shows the same, blank lock screen --
        // effectively a "clear the field" action, not an escape hatch.
        UnlockWalletPanel reVerifyPanel = new UnlockWalletPanel(
                storage, activeSession, this::onReVerified, this::onAutoLock);
        walletIsLocked = true;
        bottomRegion.add(reVerifyPanel, STATE_WALLET_LOCKED);
        bottomCardLayout.show(bottomRegion, STATE_WALLET_LOCKED);
        SwingUtilities.invokeLater(reVerifyPanel::focusPasswordField);
    }

    /** Called once a re-verify attempt (after an auto-lock) actually
     *  succeeds -- at which point UnlockWalletPanel has already called
     *  activeSession.unlock(), restoring its signing key material in
     *  place. Reuses activeSession/activeSyncLoop exactly as they
     *  already are -- neither was ever stopped or replaced by the
     *  lock, and dashboardPanel may well have kept refreshing with
     *  real, newer data the whole time this was locked, so a fresh
     *  refresh() here just makes sure whatever's now showing reflects
     *  that, not whatever was current at the moment of locking. */
    private void onReVerified() {
        walletIsLocked = false;
        bottomCardLayout.show(bottomRegion, STATE_WALLET_LOADED);
        dashboardPanel.refresh(activeSession, storage);
        auctionsPanel.refresh(activeSession);
        namesPanel.refresh(activeSession);
        historyPanel.refresh(activeSession, storage);
        lockTimer.start();
    }

    private void onWalletCreated(String walletName, WalletSession session) {
        try {
            List<WalletStorage.WalletInfo> wallets = storage.listWallets();
            WalletStorage.WalletInfo created = wallets.stream()
                    .filter(w -> w.name.equals(walletName))
                    .findFirst().orElse(null);
            // Must be set BEFORE refresh() below -- that call itself
            // triggers this class's own selection listener as a side
            // effect (see skipNextUnlockPrompt's own comment for why).
            skipNextUnlockPrompt = true;
            dropdown.refresh(wallets, created);
            relocateWalletPickerToTopBar();
            bottomCardLayout.show(bottomRegion, STATE_WALLET_LOADED);
            // The session was opened by the wizard with the password just
            // typed -- treat it exactly like a normal unlock: set it active,
            // fill the dashboard, start the sync loop and the lock timer.
            // Without this, a created/restored wallet never synced at all.
            onWalletUnlocked(session);
        } catch (SQLException ex) {
            throw new RuntimeException("Wallet was created but the wallet list could not be reloaded", ex);
        }
    }

    /** Shared by the create wizard, restore wizard, and unlock prompt --
     *  none of the three need a different cancellation behavior, all
     *  three just revert to whatever was genuinely loaded before (an
     *  already-unlocked wallet, tracked via lastRealSelection -- see
     *  that field's own comment for why it's set only on a REAL
     *  unlock, not a mere selection), or the initial picker screen if
     *  nothing was. */
    private void onWizardCancelled() {
        if (lastRealSelection != null) {
            // Setting the dropdown's selection fires its own action
            // listener, which calls onWalletSelectionChanged() again
            // and correctly shows that wallet's own loaded pane --
            // reverting through the same real path a person switching
            // wallets normally would use, not a separate shortcut.
            dropdown.setSelectedItem(lastRealSelection);
        } else {
            bottomCardLayout.show(bottomRegion, STATE_PICKER);
        }
    }

    private void navigateTo(String screen) {
        contentCardLayout.show(contentArea, screen);
    }

    private JPanel placeholderPanel(String name) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(UIManager.getColor("EasyHandshakeWallet.background"));
        JLabel label = new JLabel(name + " -- not yet built");
        label.setFont(WalletFonts.headerSemiBold(18f));
        label.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    public static void main(String[] args) throws Exception {
        WalletFonts.registerAll();
        EasyHandshakeWalletTheme.setup();

        File dataDir = new File(System.getProperty("user.home"), ".easy-handshake-wallet");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            throw new java.io.IOException("Could not create data directory: " + dataDir);
        }
        WalletStorage storage = WalletStorage.open(new File(dataDir, "wallet.db").getPath());

        SwingUtilities.invokeLater(() -> {
            try {
                WalletMainFrame frame = new WalletMainFrame(storage);
                frame.setVisible(true);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}