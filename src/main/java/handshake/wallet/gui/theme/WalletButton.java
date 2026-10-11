package handshake.wallet.gui.theme;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;

/**
 * The wallet's own styled button, in two variants -- PRIMARY for the
 * one main action on a screen (blue accent fill, beige outline, near-
 * black text, per this project's own design work), SECONDARY for
 * everything else (Cancel, Back), quieter and unfilled so the primary
 * action still reads as the obvious one to take. Only one PRIMARY
 * button is meant to appear per screen -- the same "one accent per
 * view" restraint this project has followed elsewhere, applied to
 * buttons specifically.
 */
public class WalletButton extends JButton {

    public enum Style { PRIMARY, SECONDARY }

    public WalletButton(String text, Style style) {
        super(text);
        setFont(WalletFonts.body(14f));
        setFocusPainted(false);
        setOpaque(true);

        Color accentPrimary = UIManager.getColor("EasyHandshakeWallet.accentPrimary");
        Color accentSecondary = UIManager.getColor("EasyHandshakeWallet.accentSecondary");
        Color background = UIManager.getColor("EasyHandshakeWallet.background");
        Color panelBg = UIManager.getColor("EasyHandshakeWallet.panelBackground");
        Color borderMetal = UIManager.getColor("EasyHandshakeWallet.borderMetal");
        Color silver = UIManager.getColor("EasyHandshakeWallet.silver");

        if (style == Style.PRIMARY) {
            setBackground(accentPrimary);
            // The theme's own near-black base color, not a raw #000000
            // -- reads as black in context while staying a real,
            // already-established token rather than a new one-off hex
            // value introduced just for this.
            setForeground(background);
            setBorder(new CompoundBorder(
                    new LineBorder(accentSecondary, 1),
                    new EmptyBorder(8, 20, 8, 20)));
        } else {
            setBackground(panelBg);
            setForeground(silver);
            setBorder(new CompoundBorder(
                    new LineBorder(borderMetal, 1),
                    new EmptyBorder(8, 20, 8, 20)));
        }
    }
}