package handshake.wallet.gui.theme;

import handshake.wallet.WalletStorage;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The top bar's real wallet picker -- every wallet actually present in
 * storage (across every currency, "name - Currency (CODE)"), a divider,
 * then "Create a new wallet" / "Restore a previous wallet" as trailing
 * actions. Replaces the earlier CurrencyDropdown entirely: that one
 * picked between a hardcoded, partly-disabled pair of currencies; this
 * one picks between real, dynamically-loaded wallets, which turned out
 * to be the actually-correct model once multi-currency support was
 * thought through (one wallet is one name, one seed, one currency --
 * see this project's own design discussion on why).
 */
public class WalletPickerDropdown extends JComboBox<WalletPickerDropdown.Entry> {

    public enum Kind { WALLET, DIVIDER, ACTION }

    public static final class Entry {
        public final Kind kind;
        public final String displayText;
        public final WalletStorage.WalletInfo wallet; // non-null only when kind == WALLET

        private Entry(Kind kind, String displayText, WalletStorage.WalletInfo wallet) {
            this.kind = kind;
            this.displayText = displayText;
            this.wallet = wallet;
        }

        static Entry wallet(WalletStorage.WalletInfo w) {
            return new Entry(Kind.WALLET, w.name + " - " + CurrencyDisplay.name(w.currency), w);
        }

        static Entry divider() {
            return new Entry(Kind.DIVIDER, "", null);
        }

        static Entry action(String text) {
            return new Entry(Kind.ACTION, text, null);
        }

        // JComboBox's own fallback text representation when no custom
        // renderer is in play (e.g. accessibility tooling) -- kept
        // correct as a matter of course, even though the real rendering
        // below never actually relies on it.
        @Override
        public String toString() { return displayText; }
    }

    public static final String ACTION_CREATE = "Create a new wallet";
    public static final String ACTION_RESTORE = "Restore a previous wallet";

    public WalletPickerDropdown(List<WalletStorage.WalletInfo> wallets) {
        super(buildModel(wallets));
        setFont(WalletFonts.body(16f));
        // Beige for the current selection, same treatment already
        // established for the active sidebar nav item and the old
        // currency dropdown's own selected value.
        setForeground(UIManager.getColor("EasyHandshakeWallet.accentSecondary"));
        setRenderer(new EntryRenderer());
        if (getItemCount() > 0) setSelectedIndex(0);
    }

    private static Entry[] buildModel(List<WalletStorage.WalletInfo> wallets) {
        List<Entry> entries = new ArrayList<>();
        for (WalletStorage.WalletInfo w : wallets) entries.add(Entry.wallet(w));
        // Only inserted when there's actually something to divide from
        // -- a true first run (zero wallets) shows just the two actions
        // with no orphaned divider sitting above them.
        if (!wallets.isEmpty()) entries.add(Entry.divider());
        entries.add(Entry.action(ACTION_CREATE));
        entries.add(Entry.action(ACTION_RESTORE));
        return entries.toArray(new Entry[0]);
    }

    /** Rebuilds this dropdown's model in place -- after a wallet is
     *  created or restored, so it appears in the list without needing
     *  to destroy and recreate this whole component (which would lose
     *  its position in the layout momentarily). selectAfter, when
     *  given, becomes the new selection once the rebuild is done --
     *  the wallet that was just created, say -- matched by name since
     *  the old Entry object for it no longer exists after the rebuild. */
    public void refresh(List<WalletStorage.WalletInfo> wallets, WalletStorage.WalletInfo selectAfter) {
        removeAllItems();
        for (Entry e : buildModel(wallets)) addItem(e);
        if (selectAfter != null) {
            for (int i = 0; i < getItemCount(); i++) {
                Entry e = getItemAt(i);
                if (e.wallet != null && e.wallet.name.equals(selectAfter.name)) {
                    setSelectedItem(e);
                    break;
                }
            }
        }
    }

    @Override
    public void setSelectedItem(Object item) {
        // Dividers are never a real selection -- same "silently refuse,
        // don't throw" pattern the old CurrencyDropdown used for its
        // disabled item, since attempting to select a divider isn't a
        // caller error to raise, just a no-op.
        if (item instanceof Entry e && e.kind == Kind.DIVIDER) return;
        super.setSelectedItem(item);
    }

    private static class EntryRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            if (value instanceof Entry e && e.kind == Kind.DIVIDER) {
                // A real JSeparator, not a disabled-looking label --
                // reads unambiguously as a divider rather than a greyed-
                // out, unexplained row.
                JSeparator separator = new JSeparator();
                separator.setForeground(UIManager.getColor("EasyHandshakeWallet.borderMetal"));
                separator.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                return separator;
            }

            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            Entry e = (Entry) value;
            label.setText(e.displayText);
            label.setFont(WalletFonts.body(16f));
            label.setEnabled(true);
            // Beige only on the normal, unhighlighted rows -- not
            // unconditionally on every row regardless of state. When
            // isSelected is true (the row currently under the mouse or
            // keyboard focus within the open popup), the super call
            // above already set a foreground the look-and-feel chose
            // specifically to read clearly against its own selection-
            // highlight background; overwriting that with beige
            // (a color never designed against that background) is
            // exactly what produced the low-contrast result seen here.
            // Better to defer to FlatLaf's own real contrast pairing for
            // that one state than pick a fixed alternate color by eye.
            if (!isSelected) {
                label.setForeground(UIManager.getColor("EasyHandshakeWallet.accentSecondary"));
            }
            return label;
        }
    }
}