package handshake.wallet.gui.panels;

import handshake.wallet.WalletAuctionMonitor;
import handshake.wallet.WalletSession;
import handshake.wallet.WalletStorage;
import handshake.wallet.gui.theme.WalletButton;
import handshake.wallet.gui.theme.WalletFonts;
import handshake.wallet.gui.theme.WalletPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The wallet's main dashboard -- balance, alerts (surfacing whatever
 * WalletAuctionMonitor/WalletNameMonitor produce), recent activity, and
 * active auctions.
 * <p>
 * Built once, before any wallet is unlocked (see WalletMainFrame's own
 * constructor -- buildWalletLoadedPane() runs there before a session
 * exists at all), then refresh(session, storage) updates it with real
 * data the moment a session becomes available (WalletMainFrame's own
 * onWalletUnlocked()). This is why every piece that needs to change is
 * kept as a field rather than a constructor-local variable -- balance
 * updates one label in place; activity, alerts, and auctions rebuild
 * their containers' contents each call, since those are variable-length
 * lists, not fixed values.
 * <p>
 * Deliberately reads only already-loaded, in-memory session state here
 * -- no network call. Renewal alerts (checkRenewalDeadlines) are safe
 * to recompute fresh every refresh() since they only need a height and
 * already-loaded name state; auction alerts (drainAlerts) only ever
 * hold something once an actual sync pass has pushed some there via
 * WalletAuctionMonitor.update() -- an empty auction-alerts list right
 * after unlocking a wallet that hasn't synced yet is correct, not a
 * bug, since there's genuinely nothing to report yet.
 */
public class DashboardPanel extends JPanel {

    private final JLabel balanceValueLabel;
    private final JLabel domainsValueLabel;
    private final JPanel alertsContainer;
    private final WalletPanel activityPanel;
    private final JPanel activityRows;

    public DashboardPanel() {
        setLayout(new BorderLayout());
        setBackground(UIManager.getColor("EasyHandshakeWallet.background"));
        setBorder(new EmptyBorder(28, 32, 28, 32));

        // Balance now stands on its own -- the currency picker it used
        // to sit beside moved up into TopBar entirely (a wallet is now
        // a fixed, single-currency thing chosen there, not something
        // switched per-screen down here).
        // Title on the left; on the right, Balance with Domains underneath,
        // both right-aligned and in the same label font/colors.
        JLabel pageTitle = new JLabel("Dashboard");
        pageTitle.setFont(WalletFonts.headerSemiBold(22f));
        pageTitle.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));

        balanceValueLabel = new JLabel("-- HNS");
        balanceValueLabel.setFont(WalletFonts.mono(16f));
        balanceValueLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.accentPrimary"));
        domainsValueLabel = new JLabel("--");
        domainsValueLabel.setFont(WalletFonts.mono(16f));
        domainsValueLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.accentPrimary"));

        JPanel stats = new JPanel(new GridBagLayout());
        stats.setOpaque(false);
        addStatRow(stats, 0, "Balance: ", balanceValueLabel);
        addStatRow(stats, 1, "Domains: ", domainsValueLabel);

        JPanel topSection = new JPanel(new BorderLayout());
        topSection.setOpaque(false);
        topSection.setBorder(new EmptyBorder(0, 0, 20, 0));
        topSection.add(pageTitle, BorderLayout.WEST);
        topSection.add(stats, BorderLayout.EAST);

        add(topSection, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        alertsContainer = new JPanel();
        alertsContainer.setOpaque(false);
        alertsContainer.setLayout(new BoxLayout(alertsContainer, BoxLayout.Y_AXIS));
        alertsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(alertsContainer);

        activityPanel = new WalletPanel("Recent activity");
        activityRows = new JPanel();
        activityRows.setLayout(new BoxLayout(activityRows, BoxLayout.Y_AXIS));
        activityRows.setOpaque(false);
        activityPanel.setContent(activityRows);

        // Recent activity spans the full width of the main panel (auctions
        // now have their own screen, "Auctions", in the sidebar).
        JPanel full = new JPanel(new BorderLayout());
        full.setOpaque(false);
        full.setAlignmentX(Component.LEFT_ALIGNMENT);
        full.add(activityPanel, BorderLayout.CENTER);
        body.add(Box.createVerticalStrut(16));
        body.add(full);

        add(body, BorderLayout.CENTER);
    }

    private static void addStatRow(JPanel p, int row, String text, JLabel value) {
        JLabel label = new JLabel(text);
        label.setFont(WalletFonts.body(16f));
        label.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.anchor = GridBagConstraints.EAST;
        c.gridx = 0;
        p.add(label, c);
        c.gridx = 1;
        p.add(value, c);
    }

    /**
     * Updates every piece of this panel from a real, unlocked session
     * -- called from WalletMainFrame.onWalletUnlocked(), and safe to
     * call again later once a real background sync loop exists (a
     * separate, later task -- see WalletMainFrame's own top comment).
     * Each call is a full, fresh snapshot of current state, not an
     * incremental update -- simplest correct behavior given how
     * infrequently this actually runs today (once, at unlock).
     */
    public void refresh(WalletSession session, WalletStorage storage) {
        balanceValueLabel.setText(formatHns(session.utxoSet.getBalance()));
        domainsValueLabel.setText(String.valueOf(session.ownedNames().size()));

        List<WalletAuctionMonitor.Alert> alerts = new ArrayList<>();
        alerts.addAll(session.nameMonitor.checkRenewalDeadlines(session.headerChain.tipHeight()));
        alerts.addAll(session.auctionMonitor.drainAlerts());
        rebuildAlerts(alerts);

        List<WalletStorage.ActivityRecord> activity;
        try {
            activity = storage.loadRecentActivity(session.walletName, session.currency, 10);
        } catch (SQLException e) {
            activity = List.of(); // best-effort for a dashboard view -- a load failure here shouldn't crash the whole panel
        }
        rebuildActivity(activity);

        revalidate();
        repaint();
    }

    private void rebuildAlerts(List<WalletAuctionMonitor.Alert> alerts) {
        alertsContainer.removeAll();
        for (int i = 0; i < alerts.size(); i++) {
            WalletAuctionMonitor.Alert alert = alerts.get(i);
            alertsContainer.add(buildAlert(alertTitle(alert), alert.message));
            if (i < alerts.size() - 1) alertsContainer.add(Box.createVerticalStrut(8));
        }
    }

    /** A short, human title per alert type -- the alert's own .message
     *  is already the full explanatory sentence (see
     *  WalletAuctionMonitor/WalletNameMonitor, which compose it
     *  directly), so this only needs to summarize, matching the
     *  mockup's own two-line "title, then detail" alert shape. */
    private String alertTitle(WalletAuctionMonitor.Alert alert) {
        return switch (alert.type) {
            case MAY_HAVE_BEEN_OUTBID -> "May have been outbid on \"" + alert.name + "\"";
            case REVEAL_DEADLINE_APPROACHING -> "Reveal deadline approaching for \"" + alert.name + "\"";
            case LOST_AUCTION -> "Lost the auction for \"" + alert.name + "\"";
            case WON_AUCTION -> "Won the auction for \"" + alert.name + "\"";
            // Renewal alerts come from WalletNameMonitor, a different
            // Alert-producing class reusing the same Alert type -- its
            // own .type is always one of the four above by
            // construction, so this default is unreachable rather than
            // a real gap; kept only so this switch stays exhaustive
            // against a future fifth AlertType without a compile error
            // silently becoming a runtime one instead.
            default -> alert.name != null ? alert.name : "Alert";
        };
    }

    private void rebuildActivity(List<WalletStorage.ActivityRecord> activity) {
        activityRows.removeAll();
        if (activity.isEmpty()) {
            activityRows.add(emptyStateLabel("No activity yet."));
        } else {
            for (WalletStorage.ActivityRecord r : activity) {
                String detail = r.height >= WalletStorage.PENDING_HEIGHT ? "pending \u00B7 " + r.label : r.label;
                activityRows.add(buildRow(r.kind, detail, formatSignedHns(r.amount), r.amount >= 0));
            }
        }
    }

    static JComponent emptyStateLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(WalletFonts.body(12f));
        label.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        label.setBorder(new EmptyBorder(8, 0, 8, 0));
        return label;
    }

    /** Dollarydoos (millionths of 1 HNS, confirmed real hsd unit) to a
     *  display string -- e.g. "128,450.220 HNS". */
    static String formatHns(long dollarydoos) {
        return String.format("%,.3f HNS", dollarydoos / 1_000_000.0);
    }

    /** Same as formatHns(), with an explicit +/- sign -- what the
     *  activity feed's own amount column shows (matching the mockup's
     *  "+ 5,000.00" / "- 1,240.50" convention), since a plain
     *  formatHns() on a negative value already includes a minus sign
     *  but no matching plus sign on a positive one. */
    private static String formatSignedHns(long dollarydoos) {
        String sign = dollarydoos >= 0 ? "+ " : "- ";
        return sign + String.format("%,.3f", Math.abs(dollarydoos) / 1_000_000.0);
    }

    private JComponent buildAlert(String title, String body) {
        JPanel alert = new JPanel(new BorderLayout());
        Color panelBg = UIManager.getColor("EasyHandshakeWallet.panelBackground");
        Color borderMetal = UIManager.getColor("EasyHandshakeWallet.borderMetal");
        Color accentSecondary = UIManager.getColor("EasyHandshakeWallet.accentSecondary");

        alert.setBackground(panelBg);
        alert.setAlignmentX(Component.LEFT_ALIGNMENT);
        alert.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        alert.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createCompoundBorder(
                        new LineBorderAllSides(borderMetal),
                        new MatteBorder(0, 2, 0, 0, accentSecondary)),
                new EmptyBorder(10, 12, 10, 12)));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(WalletFonts.body(13f));
        titleLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));

        JLabel bodyLabel = new JLabel(body);
        bodyLabel.setFont(WalletFonts.body(12f));
        bodyLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));

        JPanel textStack = new JPanel();
        textStack.setOpaque(false);
        textStack.setLayout(new BoxLayout(textStack, BoxLayout.Y_AXIS));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        bodyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        textStack.add(titleLabel);
        textStack.add(bodyLabel);

        alert.add(textStack, BorderLayout.CENTER);
        return alert;
    }

    /** One list row: a fixed-width label column, a flexible detail column that
     *  takes the remaining width (left-aligned, truncated with "..." if too
     *  long), and a right-aligned amount. */
    static JComponent buildRow(String label, String detail, String amount, boolean positive) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setBorder(new MatteBorder(0, 0, 1, 0, UIManager.getColor("EasyHandshakeWallet.borderMetal")));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        row.setPreferredSize(new Dimension(100, 34));

        JLabel labelComp = new JLabel(label);
        labelComp.setFont(WalletFonts.body(13f));
        labelComp.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
        labelComp.setPreferredSize(new Dimension(150, 20));
        labelComp.setMinimumSize(new Dimension(150, 20));

        JLabel detailComp = new JLabel(detail);
        detailComp.setFont(WalletFonts.mono(11f));
        detailComp.setForeground(detail.contains("\u00B7")
                ? UIManager.getColor("EasyHandshakeWallet.accentPrimary")
                : UIManager.getColor("EasyHandshakeWallet.silver"));
        detailComp.setMinimumSize(new Dimension(20, 20)); // lets it shrink and truncate instead of pushing the others
        detailComp.setToolTipText(detail);

        JLabel amountComp = new JLabel(amount);
        amountComp.setFont(WalletFonts.monoMedium(13f));
        amountComp.setForeground(positive
                ? UIManager.getColor("EasyHandshakeWallet.accentSecondary")
                : UIManager.getColor("EasyHandshakeWallet.silver"));
        amountComp.setHorizontalAlignment(SwingConstants.RIGHT);

        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0; g.weightx = 0; row.add(labelComp, g);
        g.gridx = 1; g.weightx = 1; g.insets = new Insets(0, 12, 0, 12); row.add(detailComp, g);
        g.gridx = 2; g.weightx = 0; g.insets = new Insets(0, 0, 0, 0); row.add(amountComp, g);
        return row;
    }

    /** A simple all-sides line border -- javax.swing.border.LineBorder
     *  already does this, but named locally here to make the compound-
     *  border stacking above unambiguous about which border is which. */
    private static class LineBorderAllSides extends javax.swing.border.LineBorder {
        LineBorderAllSides(Color color) { super(color, 1); }
    }
}