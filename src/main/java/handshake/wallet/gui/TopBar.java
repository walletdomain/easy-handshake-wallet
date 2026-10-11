package handshake.wallet.gui;

import handshake.wallet.gui.theme.WalletFonts;
import handshake.wallet.gui.theme.WalletPickerDropdown;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;

/**
 * The wallet's fixed top bar -- app name, and (once a wallet has
 * genuinely been unlocked this session) the wallet picker. Spans the
 * full window width, sitting above whatever the swappable bottom
 * region is currently showing.
 *
 * RE-ARCHITECTURE: no longer constructs its own wallet-picker dropdown
 * -- starts with just the brand label. WalletMainFrame owns the single,
 * live "Wallet:" label and WalletPickerDropdown instance for the whole
 * application lifetime, and installWalletPicker() below is how those
 * SAME components (not a copy, not a second instance kept in sync)
 * physically relocate into this bar the first time a wallet is
 * genuinely unlocked -- see WalletMainFrame's own comment for the full
 * reasoning on why one shared, reparented instance is the right way to
 * build this: picking a wallet from the centered screen shown before
 * anything is unlocked, and picking one from this bar afterward, are
 * the exact same action, driven by the exact same listener, on the
 * exact same component. Before that first relocation, this bar simply
 * doesn't show a wallet picker at all, matching the design this was
 * built for directly: the picker lives centered in the main panel
 * until something is actually loaded, not up here from the start.
 */
public class TopBar extends JPanel {

    private final JPanel left;
    private final JLabel syncStatus;

    public TopBar() {
        setLayout(new BorderLayout());
        setBackground(UIManager.getColor("EasyHandshakeWallet.sidebarBackground"));
        setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, UIManager.getColor("EasyHandshakeWallet.borderMetal")),
                new EmptyBorder(14, 20, 14, 20)));

        JLabel brand = new JLabel("Easy Handshake Wallet");
        brand.setFont(WalletFonts.headerBold(16f));
        brand.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));

        left = new JPanel(new FlowLayout(FlowLayout.LEFT, 24, 0));
        left.setOpaque(false);
        left.add(brand);

        add(left, BorderLayout.WEST);

        // Blank until the first real sync attempt reports something --
        // see setSyncStatus()'s own comment on why this stays visible
        // and accurate through a lock, not just while unlocked.
        syncStatus = new JLabel(" ");
        syncStatus.setFont(WalletFonts.body(12f));
        syncStatus.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(syncStatus);
        add(right, BorderLayout.EAST);
    }

    /**
     * Shows the current sync status -- called from WalletMainFrame's
     * WalletSyncLoop callbacks, on every cycle, success or failure.
     * Deliberately lives here in TopBar rather than inside
     * DashboardPanel: TopBar stays visible in every state (including
     * STATE_WALLET_LOCKED -- see WalletLockTimer's own comment on why
     * background sync keeps running through a lock), so this is the
     * one place a person can always see whether syncing is actually
     * still happening, even while locked out of the rest of the UI.
     */
    public void setSyncStatus(String text, boolean isError) {
        setSyncStatus(text, isError, null);
    }

    /** As above, with optional hover text carrying the longer detail. */
    public void setSyncStatus(String text, boolean isError, String tooltip) {
        syncStatus.setText(text);
        syncStatus.setToolTipText(tooltip);
        syncStatus.setForeground(UIManager.getColor(
                isError ? "EasyHandshakeWallet.danger" : "EasyHandshakeWallet.silver"));
    }

    /** Physically relocates the given, already-constructed "Wallet:"
     *  label and dropdown into this bar -- called exactly once per
     *  application run, by WalletMainFrame, the first time a wallet is
     *  genuinely unlocked (see its own comment). Callers are
     *  responsible for removing these components from wherever they
     *  were mounted before calling this -- this method only adds them
     *  here, it doesn't know or care where they came from. */
    public void installWalletPicker(JLabel walletLabel, WalletPickerDropdown dropdown) {
        left.add(walletLabel);
        left.add(dropdown);
        left.revalidate();
        left.repaint();
    }
}