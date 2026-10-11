package handshake.wallet.gui.panels;

import handshake.wallet.WalletCrypto;
import handshake.wallet.WalletSession;
import handshake.wallet.WalletStorage;
import handshake.wallet.gui.theme.WalletButton;
import handshake.wallet.gui.theme.WalletFonts;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * The "enter your password to unlock this wallet" screen -- shown
 * whenever a wallet is picked (from the centered picker screen shown
 * before anything is unlocked, or later from TopBar's own dropdown
 * once relocated there -- see WalletMainFrame's own comment on that
 * relocation) that isn't already the one currently loaded, AND reused
 * (in a second, "re-verify" mode -- see the two-constructor split
 * below) for confirming a password again after an inactivity auto-lock
 * of an ALREADY-open wallet (see WalletLockTimer). The first mode
 * calls WalletSession.open(), this project's own real, fully-built
 * "load everything a wallet needs" operation (address state, UTXOs,
 * pending bids, name state, the header chain), not just a password
 * check -- see WalletSession's own comment. The second mode calls
 * WalletSession.unlock() on the ALREADY-open session instead --
 * re-deriving and restoring its destroy()ed signing key material in
 * place (see WalletSession's own lock()/unlock() comments), never
 * rebuilding the session or touching anything sync already changed
 * while it was locked.
 *
 * WalletSession.open()/unlock() only read from local SQLite storage
 * here (no network) -- confirmed directly from their own
 * implementation -- so calling either synchronously, on the Swing
 * event thread, right from this button's own action handler, matches
 * the same pattern already used elsewhere in this codebase for
 * storage.listWallets()/createWallet(). That's a different thing from
 * WalletSession.syncOnce() (the actual network sync), which
 * WalletMainFrame's own class comment already, correctly, flags as
 * needing to stay off this thread -- not what's happening here.
 */
public class UnlockWalletPanel extends JPanel {

    private final WalletStorage storage;
    private final String walletDisplayName;
    private final Consumer<WalletSession> onUnlocked;
    private final WalletSession sessionToUnlock; // re-verify mode only; null in first-unlock mode
    private final Runnable onReVerified;
    private final Runnable onCancel;

    private final JPasswordField passwordField = new JPasswordField();
    private final JLabel errorLabel = new JLabel(" ");
    private final JButton unlockButton = new WalletButton("Unlock", WalletButton.Style.PRIMARY);

    /** First-unlock mode: verifies the passphrase AND builds a whole
     *  fresh WalletSession via WalletSession.open() -- see this
     *  class's own top comment for why that's the right call here,
     *  not just a password check. */
    public UnlockWalletPanel(WalletStorage storage, WalletStorage.WalletInfo walletInfo,
                             Consumer<WalletSession> onUnlocked, Runnable onCancel) {
        this(storage, walletInfo.name, onUnlocked, null, null, onCancel);
    }

    /** Re-verify mode: restores an ALREADY-open session's destroy()ed
     *  signing key material in place (see WalletSession.unlock()'s own
     *  comment) and calls onReVerified with no argument -- the caller
     *  (WalletMainFrame, re-showing a wallet after an inactivity
     *  auto-lock) already has the real session in hand and just wants
     *  it restored, not a second, redundant one built. */
    public UnlockWalletPanel(WalletStorage storage, WalletSession sessionToUnlock,
                             Runnable onReVerified, Runnable onCancel) {
        this(storage, sessionToUnlock.walletName, null, sessionToUnlock, onReVerified, onCancel);
    }

    private UnlockWalletPanel(WalletStorage storage, String walletDisplayName, Consumer<WalletSession> onUnlocked,
                              WalletSession sessionToUnlock, Runnable onReVerified, Runnable onCancel) {
        this.storage = storage;
        this.walletDisplayName = walletDisplayName;
        this.onUnlocked = onUnlocked;
        this.sessionToUnlock = sessionToUnlock;
        this.onReVerified = onReVerified;
        this.onCancel = onCancel;

        setLayout(new GridBagLayout());
        setOpaque(false);
        setBackground(UIManager.getColor("EasyHandshakeWallet.background"));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setMaximumSize(new Dimension(380, Integer.MAX_VALUE));

        JLabel title = new JLabel("Unlock \"" + walletDisplayName + "\"");
        title.setFont(WalletFonts.headerSemiBold(20f));
        title.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel hint = new JLabel("Enter this wallet's password to unlock it on this device.");
        hint.setFont(WalletFonts.body(13f));
        hint.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(new EmptyBorder(6, 0, 20, 0));

        JLabel passwordLabel = new JLabel("Password");
        passwordLabel.setFont(WalletFonts.body(12f));
        passwordLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        passwordLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        passwordLabel.setBorder(new EmptyBorder(0, 0, 4, 0));

        passwordField.setFont(WalletFonts.body(15f));
        passwordField.setAlignmentX(Component.LEFT_ALIGNMENT);
        passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        // Enter submits, the same way a login field ordinarily behaves
        // -- someone unlocking a wallet shouldn't need to reach for the
        // mouse for the single, expected action here.
        passwordField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) attemptUnlock();
            }
        });

        errorLabel.setFont(WalletFonts.body(12f));
        errorLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.danger"));
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        errorLabel.setBorder(new EmptyBorder(10, 0, 20, 0));

        JButton cancelButton = new WalletButton("Cancel", WalletButton.Style.SECONDARY);
        cancelButton.addActionListener(e -> onCancel.run());

        unlockButton.addActionListener(this::handleUnlockClicked);

        // Side by side, directly under the password field -- Unlock and
        // Cancel are the only two actions this screen has, so both sit
        // in one row rather than a taller, single-column stack.
        JPanel buttonRow = new JPanel(new GridLayout(1, 2, 10, 0));
        buttonRow.setOpaque(false);
        buttonRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        buttonRow.add(unlockButton);
        buttonRow.add(cancelButton);

        content.add(title);
        content.add(hint);
        content.add(passwordLabel);
        content.add(passwordField);
        content.add(errorLabel);
        content.add(buttonRow);
        add(content);
    }

    /** Called once this panel is actually showing, so the password
     *  field has real, visible keyboard focus without the person
     *  needing to click into it first -- consistent with how a login
     *  screen ordinarily behaves. WalletMainFrame calls this right
     *  after swapping this panel into view (see its own comment on why
     *  this can't just happen in the constructor: a component not yet
     *  attached to a visible window can't correctly receive focus). */
    public void focusPasswordField() {
        passwordField.requestFocusInWindow();
    }

    private void handleUnlockClicked(ActionEvent e) {
        attemptUnlock();
    }

    private void attemptUnlock() {
        char[] password = passwordField.getPassword();
        try {
            if (onUnlocked != null) {
                WalletSession session = WalletSession.open(storage, walletDisplayName, new String(password));
                onUnlocked.accept(session);
            } else {
                // Re-verify mode -- actually restores sessionToUnlock's
                // own destroyed key material in place (see
                // WalletSession.unlock()'s own comment), not just a
                // verify-and-discard check.
                sessionToUnlock.unlock(storage, new String(password));
                onReVerified.run();
            }
        } catch (WalletCrypto.WrongPassphraseException ex) {
            errorLabel.setText("Incorrect password.");
            passwordField.setText("");
            passwordField.requestFocusInWindow();
        } catch (SQLException ex) {
            // A real storage-layer failure (a corrupted database file,
            // a disk error) -- genuinely different from "wrong
            // password" and worth saying so plainly, rather than
            // implying the person mistyped something they didn't.
            errorLabel.setText("Could not unlock this wallet: " + ex.getMessage());
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}