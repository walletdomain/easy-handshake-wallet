package handshake.wallet.gui.theme;

import javax.swing.*;
import java.awt.*;

/**
 * A horizontal strength indicator for the password step -- purely
 * informational, since this wallet deliberately enforces no password
 * requirements at all (a person's own choice on their own device).
 * Fill color interpolates from the theme's blue accent at low strength
 * to its beige accent at high strength, growing in width as the
 * password gets longer and more varied.
 */
public class PasswordStrengthBar extends JComponent {

    private float strength = 0f; // 0.0 (empty/no password) to 1.0 (strong)

    public PasswordStrengthBar() {
        setPreferredSize(new Dimension(200, 8));
        setOpaque(false);
    }

    public void setStrength(float strength) {
        this.strength = Math.max(0f, Math.min(1f, strength));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int width = getWidth();
        int height = getHeight();

        g2.setColor(UIManager.getColor("EasyHandshakeWallet.panelBackground"));
        g2.fillRect(0, 0, width, height);
        g2.setColor(UIManager.getColor("EasyHandshakeWallet.borderMetal"));
        g2.drawRect(0, 0, width - 1, height - 1);

        if (strength > 0f) {
            int fillWidth = Math.max(2, Math.round(width * strength));
            Color weak = UIManager.getColor("EasyHandshakeWallet.accentPrimary");
            Color strong = UIManager.getColor("EasyHandshakeWallet.accentSecondary");
            g2.setColor(interpolate(weak, strong, strength));
            g2.fillRect(1, 1, Math.max(0, fillWidth - 2), height - 2);
        }

        g2.dispose();
    }

    private static Color interpolate(Color from, Color to, float t) {
        int r = Math.round(from.getRed() + (to.getRed() - from.getRed()) * t);
        int gr = Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t);
        int b = Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t);
        return new Color(r, gr, b);
    }

    /** A simple, transparent heuristic -- not a rigorous entropy
     *  calculation, just an honest, explainable signal since no hard
     *  requirement is enforced anywhere in this flow. Length
     *  contributes most of the score (full credit at 16+ characters);
     *  character-class variety (lower/upper/digit/symbol) contributes
     *  the rest, but is itself damped for very short passwords -- a
     *  single character trivially "has variety" in whichever one class
     *  it happens to fall into, and shouldn't score meaningfully higher
     *  for that alone. Full variety credit only applies once there's
     *  at least 8 characters to actually spread that variety across. */
    public static float score(char[] password) {
        if (password == null || password.length == 0) return 0f;

        boolean hasLower = false, hasUpper = false, hasDigit = false, hasSymbol = false;
        for (char c : password) {
            if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSymbol = true;
        }
        int varietyCount = (hasLower ? 1 : 0) + (hasUpper ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSymbol ? 1 : 0);

        float lengthScore = Math.min(1f, password.length / 16f) * 0.6f;
        float varietyDamping = Math.min(1f, password.length / 8f);
        float varietyScore = (varietyCount / 4f) * 0.4f * varietyDamping;
        return Math.min(1f, lengthScore + varietyScore);
    }
}