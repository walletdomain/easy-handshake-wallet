package handshake.wallet.gui.panels;

import handshake.wallet.WalletSession;
import handshake.wallet.gui.theme.WalletFonts;
import handshake.wallet.gui.theme.WalletPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;
import java.util.Locale;

/**
 * The "Names" screen: only the names this wallet owns right now. A name that
 * was transferred away has no unspent name coin any more, so it does not
 * appear here (it still shows on the History screen).
 */
public class NamesPanel extends JPanel {

    private final JPanel rows;
    private final JLabel countLabel;
    private final JTextField filterField;
    private List<WalletSession.OwnedName> current = List.of();

    public NamesPanel() {
        setLayout(new BorderLayout());
        setBackground(UIManager.getColor("EasyHandshakeWallet.background"));
        setBorder(new EmptyBorder(28, 32, 28, 32));

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(0, 0, 20, 0));
        JLabel pageTitle = new JLabel("Names");
        pageTitle.setFont(WalletFonts.headerSemiBold(22f));
        pageTitle.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
        header.add(pageTitle, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        right.setOpaque(false);
        countLabel = new JLabel(" ");
        countLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        filterField = new JTextField(16);
        filterField.putClientProperty("JTextField.placeholderText", "Filter names");
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { rebuild(); }
            public void removeUpdate(DocumentEvent e) { rebuild(); }
            public void changedUpdate(DocumentEvent e) { rebuild(); }
        });
        right.add(countLabel);
        right.add(filterField);
        header.add(right, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        WalletPanel panel = new WalletPanel("Names you own");
        rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        JScrollPane scroll = new JScrollPane(rows);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.setContent(scroll);
        add(panel, BorderLayout.CENTER);
    }

    public void refresh(WalletSession session) {
        current = session.ownedNames();
        rebuild();
    }

    private void rebuild() {
        rows.removeAll();
        String f = filterField.getText().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (WalletSession.OwnedName o : current) {
            if (!f.isEmpty() && !o.name.toLowerCase(Locale.ROOT).contains(f)) continue;
            rows.add(DashboardPanel.buildRow(o.name + "/", statusFor(o), DashboardPanel.formatHns(o.coinValue), true));
            shown++;
        }
        if (shown == 0) {
            rows.add(DashboardPanel.emptyStateLabel(current.isEmpty() ? "No names owned." : "No names match."));
        }
        countLabel.setText(f.isEmpty() ? current.size() + " names" : shown + " of " + current.size());
        rows.revalidate();
        rows.repaint();
    }

    private static String statusFor(WalletSession.OwnedName o) {
        String s;
        switch (o.covenantType) {
            case 6: s = "registered"; break;
            case 7: s = "updated"; break;
            case 8: s = "renewed"; break;
            case 9: s = "transfer pending"; break;
            case 10: s = "transferred in"; break;
            default: s = "owned"; break;
        }
        return s + " · block " + String.format("%,d", o.height);
    }
}