package handshake.wallet.gui.theme;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;

/**
 * The wallet's reusable "engraved panel" surface -- the one deliberate
 * departure from generic Swing/SaaS defaults established in this
 * project's own design work: a thin metallic hairline border on a
 * slightly raised background, no soft drop shadow, no heavy rounding.
 * Every card-like surface in the app (balance, activity, alerts) is
 * built from this same component so the look stays consistent.
 */
public class WalletPanel extends JPanel {

    private JLabel titleLabel;

    public WalletPanel() {
        this(null);
    }

    public WalletPanel(String title) {
        setLayout(new BorderLayout());
        setBackground(UIManager.getColor("EasyHandshakeWallet.panelBackground"));
        setBorder(new CompoundBorder(
                new LineBorder(UIManager.getColor("EasyHandshakeWallet.borderMetal"), 1),
                new EmptyBorder(16, 18, 16, 18)));

        if (title != null) {
            titleLabel = new JLabel(title);
            titleLabel.setFont(WalletFonts.body(12f));
            titleLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
            titleLabel.setBorder(new CompoundBorder(
                    javax.swing.BorderFactory.createMatteBorder(0, 0, 2, 0, UIManager.getColor("EasyHandshakeWallet.accentSecondary")),
                    new EmptyBorder(0, 0, 8, 0)));

            JPanel titleWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            titleWrapper.setOpaque(false);
            titleWrapper.add(titleLabel);

            JPanel north = new JPanel(new BorderLayout());
            north.setOpaque(false);
            north.add(titleWrapper, BorderLayout.NORTH);
            north.setBorder(new EmptyBorder(0, 0, 12, 0));
            add(north, BorderLayout.NORTH);
        }
    }

    /** Adds content below the title, if any. */
    public void setContent(JComponent content) {
        content.setOpaque(false);
        add(content, BorderLayout.CENTER);
    }
}