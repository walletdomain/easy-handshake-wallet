package handshake.wallet.gui.panels;

import handshake.wallet.gui.theme.PasswordStrengthBar;
import handshake.wallet.gui.theme.WalletFonts;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.Arrays;

/**
 * The "set a password" step, shared between the create and restore
 * wizards -- both need the exact same thing here (a password to
 * protect the wallet's seed at rest on this device), regardless of
 * whether that seed was just generated or just entered by hand.
 */
public class WalletPasswordStep extends JPanel {

    private final JPasswordField passwordField = new JPasswordField();
    private final JPasswordField confirmField = new JPasswordField();
    private final PasswordStrengthBar strengthBar = new PasswordStrengthBar();
    private final JLabel errorLabel = new JLabel(" ");

    public WalletPasswordStep() {
        setLayout(new GridBagLayout());
        setOpaque(false);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setMaximumSize(new Dimension(420, Integer.MAX_VALUE));

        JLabel title = new JLabel("Set a password");
        title.setFont(WalletFonts.headerSemiBold(20f));
        title.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        // No length or complexity is actually enforced -- this is a
        // deliberate choice, not an oversight (see the strength meter
        // below): informational feedback instead of a hard rule,
        // trusting the person's own judgment on their own device
        // rather than blocking them.
        JLabel hint = new JLabel("Protects this wallet on this device. No specific requirements -- your choice.");
        hint.setFont(WalletFonts.body(13f));
        hint.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(new EmptyBorder(6, 0, 20, 0));

        JLabel passwordLabel = new JLabel("Password");
        styleFieldLabel(passwordLabel);
        passwordField.setFont(WalletFonts.body(15f));
        passwordField.setAlignmentX(Component.LEFT_ALIGNMENT);
        passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        passwordField.getDocument().addDocumentListener(new StrengthUpdater());

        JLabel strengthLabel = new JLabel("Password strength:");
        styleFieldLabel(strengthLabel);
        strengthLabel.setBorder(new EmptyBorder(10, 0, 4, 0));
        strengthBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        strengthBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));

        JLabel confirmLabel = new JLabel("Confirm password");
        styleFieldLabel(confirmLabel);
        confirmLabel.setBorder(new EmptyBorder(14, 0, 4, 0));
        confirmField.setFont(WalletFonts.body(15f));
        confirmField.setAlignmentX(Component.LEFT_ALIGNMENT);
        confirmField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        errorLabel.setFont(WalletFonts.body(12f));
        errorLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.danger"));
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        errorLabel.setBorder(new EmptyBorder(10, 0, 0, 0));

        content.add(title);
        content.add(hint);
        content.add(passwordLabel);
        content.add(passwordField);
        content.add(strengthLabel);
        content.add(strengthBar);
        content.add(confirmLabel);
        content.add(confirmField);
        content.add(errorLabel);
        add(content);
    }

    private void styleFieldLabel(JLabel label) {
        label.setFont(WalletFonts.body(12f));
        label.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(0, 0, 4, 0));
    }

    public char[] getPassword() {
        return passwordField.getPassword();
    }

    /** Returns a human-readable error, or null if the password is
     *  usable. The only actual requirement left is that the two fields
     *  match -- not a strength rule, just confirming the person knows
     *  what they just typed. */
    public String validateStep() {
        char[] pw = passwordField.getPassword();
        char[] confirm = confirmField.getPassword();
        try {
            if (!Arrays.equals(pw, confirm)) return "Passwords don't match.";
            return null;
        } finally {
            Arrays.fill(pw, '\0');
            Arrays.fill(confirm, '\0');
        }
    }

    public void showError(String message) {
        errorLabel.setText(message);
    }

    private class StrengthUpdater implements DocumentListener {
        @Override public void insertUpdate(DocumentEvent e) { update(); }
        @Override public void removeUpdate(DocumentEvent e) { update(); }
        @Override public void changedUpdate(DocumentEvent e) { update(); }

        private void update() {
            char[] pw = passwordField.getPassword();
            strengthBar.setStrength(PasswordStrengthBar.score(pw));
            Arrays.fill(pw, '\0');
        }
    }
}