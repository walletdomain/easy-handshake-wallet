package handshake.wallet.gui.panels;

import handshake.wallet.WalletSession;
import handshake.wallet.WalletStorage;
import handshake.wallet.gui.theme.WalletButton;
import handshake.wallet.gui.theme.WalletFonts;
import handshake.wallet.gui.theme.WalletPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "History" screen: every transaction the wallet has ever had, newest
 * first, including names that were later transferred away (those no longer
 * show on Names). Columns follow Bob Wallet's export: time, type, names,
 * value, fee, tx hash. "Export CSV" writes the same columns as Bob's file.
 */
public class HistoryPanel extends JPanel {

    private static final String[] COLS = {"Time", "Type", "Names", "Value (HNS)", "Fee (HNS)", "Transaction"};
    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter CSV_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.000'Z'").withZone(ZoneOffset.UTC);

    private List<WalletStorage.ActivityRecord> all = List.of();
    private List<WalletStorage.ActivityRecord> shown = List.of();
    private final JLabel countLabel = new JLabel(" ");
    private final JTextField filterField = new JTextField(16);
    private final Model model = new Model();
    private final JTable table = new JTable(model);

    public HistoryPanel() {
        setLayout(new BorderLayout());
        setBackground(UIManager.getColor("EasyHandshakeWallet.background"));
        setBorder(new EmptyBorder(28, 32, 28, 32));

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(0, 0, 20, 0));
        JLabel pageTitle = new JLabel("History");
        pageTitle.setFont(WalletFonts.headerSemiBold(22f));
        pageTitle.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
        header.add(pageTitle, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        right.setOpaque(false);
        countLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        filterField.putClientProperty("JTextField.placeholderText", "Filter (name, type, hash)");
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        JButton export = new WalletButton("Export CSV", WalletButton.Style.SECONDARY);
        export.addActionListener(e -> exportCsv());
        right.add(countLabel);
        right.add(filterField);
        right.add(export);
        header.add(right, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        table.setRowHeight(28);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.setOpaque(false);
        table.setAutoCreateColumnsFromModel(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(130);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        table.getColumnModel().getColumn(3).setPreferredWidth(120);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);
        table.getColumnModel().getColumn(5).setPreferredWidth(200);
        DefaultTableCellRenderer right2 = new DefaultTableCellRenderer();
        right2.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(3).setCellRenderer(right2);
        table.getColumnModel().getColumn(4).setCellRenderer(right2);
        table.setToolTipText(null);
        // Double-click a row to copy its full transaction hash.
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int row = table.getSelectedRow();
                if (row < 0 || row >= shown.size()) return;
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(shown.get(row).txid), null);
                countLabel.setText("Copied hash");
            }
        });

        WalletPanel panel = new WalletPanel("Every transaction");
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        panel.setContent(scroll);
        add(panel, BorderLayout.CENTER);
    }

    public void refresh(WalletSession session, WalletStorage storage) {
        try {
            all = storage.loadAllActivity(session.walletName, session.currency);
        } catch (SQLException e) {
            all = List.of();
        }
        applyFilter();
    }

    private void applyFilter() {
        String f = filterField.getText().trim().toLowerCase(Locale.ROOT);
        List<WalletStorage.ActivityRecord> out = new ArrayList<>();
        for (WalletStorage.ActivityRecord r : all) {
            if (f.isEmpty() || typeOf(r).toLowerCase(Locale.ROOT).contains(f)
                    || domainsOf(r).toLowerCase(Locale.ROOT).contains(f)
                    || r.txid.toLowerCase(Locale.ROOT).contains(f)) out.add(r);
        }
        shown = out;
        countLabel.setText(f.isEmpty() ? all.size() + " transactions" : shown.size() + " of " + all.size());
        model.fireTableDataChanged();
    }

    // ── row values ──────────────────────────────────────────────────────

    /** Rows saved before the History columns existed have no type; derive it. */
    private static String typeOf(WalletStorage.ActivityRecord r) {
        if (r.type != null && !r.type.isEmpty()) return r.type;
        String k = r.kind == null ? "" : r.kind;
        return switch (k) {
            case "Sent" -> "SEND";
            case "Opened" -> "OPEN";
            case "Bid" -> "BID";
            case "Revealed" -> "REVEAL";
            case "Redeemed" -> "REDEEM";
            case "Registered" -> "REGISTER";
            case "Updated" -> "UPDATE";
            case "Renewed" -> "RENEW";
            case "Transferred" -> "TRANSFER";
            case "Finalized" -> "FINALIZE";
            case "Revoked" -> "REVOKE";
            default -> "RECEIVE";
        };
    }

    private static String domainsOf(WalletStorage.ActivityRecord r) {
        if (r.domains != null && !r.domains.isEmpty()) return r.domains;
        String t = typeOf(r);
        return (t.equals("RECEIVE") || t.equals("SEND")) ? "" : (r.label == null ? "" : r.label);
    }

    /** Net HNS change excluding the fee the wallet paid. */
    private static long valueOf(WalletStorage.ActivityRecord r) {
        return r.amount + r.fee;
    }

    private static String timeOf(WalletStorage.ActivityRecord r) {
        if (r.height >= WalletStorage.PENDING_HEIGHT) return "pending";
        if (r.blockTime <= 0) return "—";
        return LOCAL_FMT.format(Instant.ofEpochSecond(r.blockTime));
    }

    private static String hns(long v) {
        return String.format("%,.6f", v / 1_000_000.0);
    }

    private static String shortHash(String h) {
        return h.length() > 20 ? h.substring(0, 10) + "…" + h.substring(h.length() - 8) : h;
    }

    private final class Model extends AbstractTableModel {
        @Override public int getRowCount() { return shown.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Object getValueAt(int row, int col) {
            WalletStorage.ActivityRecord r = shown.get(row);
            return switch (col) {
                case 0 -> timeOf(r);
                case 1 -> typeOf(r);
                case 2 -> domainsOf(r);
                case 3 -> {
                    long v = valueOf(r);
                    yield v == 0 ? "0" : (v > 0 ? "+ " : "- ") + hns(Math.abs(v));
                }
                case 4 -> r.fee == 0 ? "" : hns(r.fee);
                default -> shortHash(r.txid);
            };
        }
    }

    // ── CSV export (same columns as Bob Wallet's file) ─────────────────

    private void exportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("transaction_history.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        StringBuilder sb = new StringBuilder("time,txhash,fee,type,value,domains\n");
        for (WalletStorage.ActivityRecord r : all) {
            String time = r.blockTime > 0 ? CSV_FMT.format(Instant.ofEpochSecond(r.blockTime)) : "";
            sb.append(q(time)).append(',').append(q(r.txid)).append(',')
                    .append(q(Long.toString(r.fee))).append(',').append(q(typeOf(r))).append(',')
                    .append(q(String.format(Locale.ROOT, "%.6f", Math.abs(valueOf(r)) / 1_000_000.0)))
                    .append(',').append(q(domainsOf(r))).append('\n');
        }
        try {
            Files.writeString(fc.getSelectedFile().toPath(), sb.toString(), StandardCharsets.UTF_8);
            countLabel.setText("Exported " + all.size() + " rows");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not write the file: " + e.getMessage(),
                    "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}