package handshake.wallet.gui.panels;

import handshake.wallet.WalletStorage;
import handshake.wallet.gui.theme.WalletFonts;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.sql.SQLException;

/**
 * The "name this wallet" step, shared between the create and restore
 * wizards -- both need the exact same thing here (a unique name),
 * with no meaningful difference between creating a brand-new wallet
 * and restoring one from an existing phrase.
 */
public class WalletNameStep extends JPanel {

    private final WalletStorage storage;
    private final JTextField nameField = new JTextField();
    private final JLabel errorLabel = new JLabel(" ");

    public WalletNameStep(WalletStorage storage) {
        this.storage = storage;
        setLayout(new GridBagLayout());
        setOpaque(false);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setMaximumSize(new Dimension(420, Integer.MAX_VALUE));

        JLabel title = new JLabel("Name this wallet");
        title.setFont(WalletFonts.headerSemiBold(20f));
        title.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel hint = new JLabel("A name to tell this wallet apart from any others you add later.");
        hint.setFont(WalletFonts.body(13f));
        hint.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(new EmptyBorder(6, 0, 20, 0));

        nameField.setFont(WalletFonts.body(15f));
        nameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        errorLabel.setFont(WalletFonts.body(12f));
        errorLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.danger"));
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        errorLabel.setBorder(new EmptyBorder(8, 0, 0, 0));

        content.add(title);
        content.add(hint);
        content.add(nameField);
        content.add(errorLabel);
        add(content);
    }

    public String getWalletName() {
        return nameField.getText().trim();
    }

    /** Returns a human-readable error, or null if the name is usable.
     *  Checked against storage directly, not just "is it non-empty" --
     *  WalletStorage.createWallet() would otherwise fail later with a
     *  raw SQL constraint violation on a duplicate name, which isn't a
     *  message anyone should see. */
    public String validateStep() {
        String name = getWalletName();
        if (name.isEmpty()) return "Enter a name for this wallet.";
        try {
            for (WalletStorage.WalletInfo existing : storage.listWallets()) {
                if (existing.name.equals(name)) return "A wallet named \"" + name + "\" already exists.";
            }
        } catch (SQLException ex) {
            return "Could not check existing wallets: " + ex.getMessage();
        }
        return null;
    }

    public void showError(String message) {
        errorLabel.setText(message);
    }
}