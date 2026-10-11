package handshake.wallet.gui;

import handshake.wallet.gui.theme.WalletFonts;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The wallet's left-hand navigation sidebar -- a custom component
 * (Swing has no built-in "sidebar"), styled entirely from this
 * project's own EasyHandshakeWallet.* theme tokens rather than any
 * stock FlatLaf component defaults, matching the real mockup this was
 * designed from.
 */
public class Sidebar extends JPanel {

    private static final String[] NAV_ITEMS = {"Dashboard", "Send", "Receive", "Names", "Auctions", "History", "Settings"};

    private final Map<String, JLabel> navLabels = new LinkedHashMap<>();
    private String activeItem = "Dashboard";
    private final JLabel syncStatusLabel;
    private final JLabel syncDetailLabel;
    private final SyncBar syncBar = new SyncBar();

    public Sidebar(Consumer<String> onNavigate) {
        setLayout(new BorderLayout());
        setBackground(UIManager.getColor("EasyHandshakeWallet.sidebarBackground"));
        setPreferredSize(new Dimension(220, 0));
        setBorder(new MatteBorder(0, 0, 0, 1, UIManager.getColor("EasyHandshakeWallet.borderMetal")));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        // No brand wordmark here anymore -- TopBar carries "Easy
        // Handshake Wallet" now, above this sidebar, so repeating it
        // here would be the same duplication already avoided once for
        // the OS window title bar (see WalletMainFrame's own comment on
        // that). Nav items start right at the top instead.
        top.add(Box.createVerticalStrut(8));

        for (String item : NAV_ITEMS) {
            JLabel label = buildNavLabel(item, onNavigate);
            navLabels.put(item, label);
            top.add(label);
        }

        add(top, BorderLayout.NORTH);

        // Sync panel: status line, progress bar, detail line. Always visible;
        // the bar turns green and full once the wallet is synchronized.
        syncStatusLabel = new JLabel("Not connected");
        syncStatusLabel.setFont(WalletFonts.mono(11f));
        syncStatusLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.accentPrimary"));
        syncStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        syncDetailLabel = new JLabel(" ");
        syncDetailLabel.setFont(WalletFonts.mono(10f));
        syncDetailLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        syncDetailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        syncBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel syncWrapper = new JPanel();
        syncWrapper.setLayout(new BoxLayout(syncWrapper, BoxLayout.Y_AXIS));
        syncWrapper.setOpaque(false);
        syncWrapper.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(1, 0, 0, 0, UIManager.getColor("EasyHandshakeWallet.borderMetal")),
                new EmptyBorder(12, 20, 16, 20)));
        syncWrapper.add(syncStatusLabel);
        syncWrapper.add(Box.createVerticalStrut(6));
        syncWrapper.add(syncBar);
        syncWrapper.add(Box.createVerticalStrut(5));
        syncWrapper.add(syncDetailLabel);
        add(syncWrapper, BorderLayout.SOUTH);

        updateActiveStyling();
    }

    private JLabel buildNavLabel(String item, Consumer<String> onNavigate) {
        JLabel label = new JLabel(item);
        // 16f here, not 14f -- this is on top of WalletFonts' own global
        // +2 (see its own derive() comment), so this specific request
        // lands at 18pt total for nav items specifically, rather than
        // the 16pt every other body-text call site gets. A deliberate,
        // targeted increase just for these five labels, not a change to
        // the shared body() baseline itself.
        label.setFont(WalletFonts.body(16f));
        label.setBorder(new EmptyBorder(10, 18, 10, 20));
        label.setOpaque(true);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height + 6));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                setActive(item);
                onNavigate.accept(item);
            }
        });
        return label;
    }

    private void setActive(String item) {
        activeItem = item;
        updateActiveStyling();
    }

    private void updateActiveStyling() {
        Color panelBg = UIManager.getColor("EasyHandshakeWallet.panelBackground");
        Color sidebarBg = UIManager.getColor("EasyHandshakeWallet.sidebarBackground");
        Color silver = UIManager.getColor("EasyHandshakeWallet.silver");
        Color navActive = UIManager.getColor("EasyHandshakeWallet.navActive");

        for (Map.Entry<String, JLabel> e : navLabels.entrySet()) {
            boolean active = e.getKey().equals(activeItem);
            JLabel label = e.getValue();
            label.setBackground(active ? panelBg : sidebarBg);
            // Beige for the text itself when selected, not just the left
            // accent bar -- matches what was actually wanted once seen
            // running, distinct from the original mockup's own choice
            // (which only colored the border and kept the text neutral).
            label.setForeground(active ? navActive : silver);
            label.setBorder(BorderFactory.createCompoundBorder(
                    new MatteBorder(0, 2, 0, 0, active ? navActive : sidebarBg),
                    new EmptyBorder(10, 16, 10, 20)));
        }
    }

    /** Updates the sync panel. Call on the EDT. */
    public void setSync(SyncStatus status) {
        syncStatusLabel.setText(status.label());
        syncDetailLabel.setText(status.detail());
        syncBar.setStatus(status);
    }

    /** Thin horizontal progress bar: accent while syncing (a sliding
     *  segment when the amount is unknown), green and full when synced,
     *  muted red on error. */
    private static final class SyncBar extends JComponent {
        private SyncStatus status = SyncStatus.idle();
        private int phase = 0;
        private final javax.swing.Timer timer = new javax.swing.Timer(40, e -> { phase = (phase + 6) % 200; repaint(); });

        SyncBar() {
            setPreferredSize(new Dimension(100, 8));
            setMinimumSize(new Dimension(20, 8));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        }

        void setStatus(SyncStatus s) {
            this.status = s;
            boolean indeterminate = s.kind() == SyncStatus.Kind.WORKING && s.fraction() < 0;
            if (indeterminate && !timer.isRunning()) timer.start();
            if (!indeterminate && timer.isRunning()) timer.stop();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Color track = UIManager.getColor("EasyHandshakeWallet.borderMetal");
            Color accent = UIManager.getColor("EasyHandshakeWallet.accentPrimary");
            Color green = UIManager.getColor("EasyHandshakeWallet.success");
            if (green == null) green = new Color(0x7fb08a);
            Color danger = UIManager.getColor("EasyHandshakeWallet.danger");
            g2.setColor(track);
            g2.fillRoundRect(0, 0, w, h, h, h);
            switch (status.kind()) {
                case DONE -> { g2.setColor(green); g2.fillRoundRect(0, 0, w, h, h, h); }
                case ERROR -> {
                    g2.setColor(danger);
                    g2.fillRoundRect(0, 0, (int) Math.round(w * Math.max(0.03, status.fraction())), h, h, h);
                }
                case WORKING -> {
                    g2.setColor(accent);
                    if (status.fraction() < 0) {
                        int seg = w / 4;
                        int x = (int) ((w + seg) * (phase / 200.0)) - seg;
                        g2.setClip(0, 0, w, h);
                        g2.fillRoundRect(x, 0, seg, h, h, h);
                    } else {
                        g2.fillRoundRect(0, 0, (int) Math.round(w * Math.max(0.02, status.fraction())), h, h, h);
                    }
                }
                default -> { }
            }
            g2.dispose();
        }
    }
}