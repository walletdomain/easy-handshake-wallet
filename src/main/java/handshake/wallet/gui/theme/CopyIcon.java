package handshake.wallet.gui.theme;

import javax.swing.*;
import java.awt.*;

/**
 * A small, custom-drawn "two overlapping squares" copy icon -- built
 * directly with Graphics2D rather than a Unicode glyph. A real,
 * reported case showed the Unicode approach (U+29C9) rendering as a
 * blank placeholder box on the actual target machine -- Java's font-
 * fallback substitution didn't come through the way it's generally
 * expected to. Drawing the shape directly removes any dependency on
 * which glyphs a given system happens to have installed.
 */
public class CopyIcon implements Icon {

    private final int size;
    private final Color strokeColor;
    private final Color eraseColor;

    /** eraseColor should match whatever background the icon will be
     *  painted on top of -- used to "punch through" the back square
     *  where the front one overlaps it, the standard two-sheet copy
     *  icon look, rather than two fully overlapping outlines. */
    public CopyIcon(int size, Color strokeColor, Color eraseColor) {
        this.size = size;
        this.strokeColor = strokeColor;
        this.eraseColor = eraseColor;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(1.3f));

        int rectSize = Math.round(size * 0.62f);
        int offset = size - rectSize;

        // Back square, offset toward the top-right.
        g2.setColor(strokeColor);
        g2.drawRect(x + offset, y, rectSize, rectSize);

        // Front square, offset toward the bottom-left -- its interior
        // is erased first so it visually sits in front of the back
        // square rather than the two outlines just crossing each other.
        g2.setColor(eraseColor);
        g2.fillRect(x + 1, y + offset + 1, rectSize - 1, rectSize - 1);
        g2.setColor(strokeColor);
        g2.drawRect(x, y + offset, rectSize, rectSize);

        g2.dispose();
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}