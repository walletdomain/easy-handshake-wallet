package handshake.wallet.gui.theme;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

/**
 * The currency selector shown above the Dashboard header. Only
 * Handshake (HNS) is actually selectable for now -- Bitcoin (BTC) is
 * listed but disabled, a placeholder for a future multi-currency view
 * rather than something that works yet.
 *
 * Swing's JComboBox has no built-in concept of "this specific item is
 * disabled" (disabling the whole control disables every item, and
 * plenty of look-and-feels don't even try to render individual-item
 * disabled state) -- so this overrides setSelectedItem() to reject the
 * disabled item outright, and pairs that with a renderer that marks
 * its cell isEnabled(false) so FlatLaf paints it using the theme's own
 * disabled-text color rather than a hardcoded grey that would drift
 * from the rest of the app if the theme ever changes.
 */
public class CurrencyDropdown extends JComboBox<String> {

    private static final String HNS = "Handshake (HNS)";
    private static final String BTC = "Bitcoin (BTC)";
    private static final Set<String> DISABLED_ITEMS = Set.of(BTC);

    public CurrencyDropdown() {
        super(new String[]{HNS, BTC});
        setSelectedItem(HNS);
        // 16f, matching the currency label right next to it (and the
        // same targeted-increase pattern used for the sidebar nav
        // items) -- not the shared body() baseline other body text
        // still uses elsewhere in the app.
        setFont(WalletFonts.body(16f));
        // Beige for the currently-selected value shown in the closed
        // box -- the same "active selection reads in the accent color"
        // treatment already used for the active sidebar nav item.
        setForeground(UIManager.getColor("EasyHandshakeWallet.accentSecondary"));
        setRenderer(new DisabledItemRenderer());
    }

    @Override
    public void setSelectedItem(Object item) {
        // Silently refuses the change rather than throwing -- selecting
        // a disabled item isn't an error case a caller needs to handle,
        // it's just a no-op, the same way clicking a disabled button
        // does nothing rather than failing loudly.
        if (DISABLED_ITEMS.contains(item)) return;
        super.setSelectedItem(item);
    }

    private static class DisabledItemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            label.setFont(WalletFonts.body(16f));
            // Beige set unconditionally, on every row, before the
            // enabled/disabled branch below -- not just on the enabled
            // case -- because FlatLaf's own disabled-text painting
            // overrides whatever foreground color is set whenever
            // isEnabled() is false, the same standard Swing/LaF
            // convention this project already relies on elsewhere.
            // So Bitcoin (BTC) still renders in the theme's real
            // disabled grey despite this line, while Handshake (HNS)
            // picks up the beige normally.
            label.setForeground(UIManager.getColor("EasyHandshakeWallet.accentSecondary"));
            // Both branches set explicitly -- DefaultListCellRenderer
            // reuses a single shared JLabel instance across every row
            // it renders, so leaving the enabled case as an implicit
            // "don't touch it" default meant a disabled row rendered
            // just before an enabled one left that shared label's
            // disabled state stuck, incorrectly greying out Handshake
            // (HNS) too. Confirmed and caught directly by testing this,
            // not something safe to assume from reading the code alone.
            label.setEnabled(!DISABLED_ITEMS.contains(value));
            return label;
        }
    }
}