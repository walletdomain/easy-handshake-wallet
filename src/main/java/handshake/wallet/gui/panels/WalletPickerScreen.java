package handshake.wallet.gui.panels;

import handshake.wallet.gui.theme.WalletFonts;
import handshake.wallet.gui.theme.WalletPickerDropdown;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * The very first screen shown, before any wallet has been unlocked
 * this session -- the "Wallet:" label and picker dropdown, centered.
 * These are the EXACT SAME, LIVE components (not a copy, not a second
 * instance kept in sync) that later relocate into TopBar once a wallet
 * is genuinely loaded -- see WalletMainFrame's own comment on why one
 * shared, reparented instance is the right way to build this: picking
 * a wallet here and picking one from TopBar later are the exact same
 * action, driven by the exact same listener, on the exact same
 * component -- only where it's currently mounted differs. This class's
 * own job is narrow: lay them out centered, with a welcoming title,
 * for this one screen's own visual context.
 *
 * No separate "Next" button -- selecting an entry IS the action, the
 * same way the dropdown already behaves once it's living in TopBar.
 * Building it any other way here would mean two different interaction
 * models for what's actually one behavior on one component.
 */
public class WalletPickerScreen extends JPanel {

    public WalletPickerScreen(JLabel walletLabel, WalletPickerDropdown dropdown) {
        setLayout(new GridBagLayout());
        setOpaque(false);
        setBackground(UIManager.getColor("EasyHandshakeWallet.background"));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Welcome to Easy Handshake Wallet");
        title.setFont(WalletFonts.headerSemiBold(20f));
        title.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setBorder(new EmptyBorder(0, 0, 24, 0));

        JPanel pickerRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        pickerRow.setOpaque(false);
        pickerRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        pickerRow.add(walletLabel);
        pickerRow.add(dropdown);

        content.add(title);
        content.add(pickerRow);
        add(content);
    }
}