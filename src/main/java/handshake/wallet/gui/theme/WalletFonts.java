package handshake.wallet.gui.theme;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and registers the wallet's own bundled fonts -- Rajdhani for
 * headers, IBM Plex Sans for body text, IBM Plex Mono for every
 * address, hash, and monetary value -- rather than relying on whatever
 * happens to be installed on a given machine. None of these three are
 * standard system fonts on Windows, macOS, or Linux, so without
 * bundling them the app would silently fall back to a generic default
 * and lose the actual chosen typography entirely.
 *
 * All three are open, redistributable font families (OFL-licensed:
 * IBM Plex from IBM, Rajdhani from Indian Type Foundry) -- fetched
 * directly from Google Fonts' own official GitHub repository, not
 * approximated or substituted.
 *
 * IBM Plex Sans is bundled as its real variable-font file (Google
 * Fonts' current distribution for this family no longer ships separate
 * static-weight files) -- loaded via Font.createFont() the standard
 * way, which resolves to its default (Regular) named instance. That's
 * an intentional, low-risk simplification: body text in this theme is
 * predominantly Regular weight anyway, and the handful of places that
 * need real emphasis (buttons, the brand wordmark) use Rajdhani's own
 * genuine static Bold/SemiBold instead of asking Java to interpolate a
 * variable axis at runtime.
 */
public final class WalletFonts {

    private static final Map<String, Font> loaded = new HashMap<>();
    private static boolean registered = false;

    private WalletFonts() {}

    /** Loads and registers every bundled font with the JVM's graphics
     *  environment -- call once, before building any UI. Safe to call
     *  more than once; only registers fonts the first time. */
    public static synchronized void registerAll() {
        if (registered) return;
        registered = true;

        loadFont("rajdhani-medium", "Rajdhani-Medium.ttf");
        loadFont("rajdhani-semibold", "Rajdhani-SemiBold.ttf");
        loadFont("rajdhani-bold", "Rajdhani-Bold.ttf");
        loadFont("plex-sans", "IBMPlexSans-VariableFont.ttf");
        loadFont("plex-mono", "IBMPlexMono-Regular.ttf");
        loadFont("plex-mono-medium", "IBMPlexMono-Medium.ttf");
    }

    private static void loadFont(String key, String resourceName) {
        try (InputStream in = WalletFonts.class.getResourceAsStream(resourceName)) {
            if (in == null) {
                System.err.println("[WalletFonts] Missing bundled font resource: " + resourceName);
                return;
            }
            Font font = Font.createFont(Font.TRUETYPE_FONT, in);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            loaded.put(key, font);
        } catch (Exception e) {
            System.err.println("[WalletFonts] Failed to load " + resourceName + ": " + e);
        }
    }

    public static Font headerBold(float size) {
        return derive("rajdhani-bold", size);
    }

    public static Font headerSemiBold(float size) {
        return derive("rajdhani-semibold", size);
    }

    public static Font headerMedium(float size) {
        return derive("rajdhani-medium", size);
    }

    public static Font body(float size) {
        return derive("plex-sans", size);
    }

    public static Font mono(float size) {
        return derive("plex-mono", size);
    }

    public static Font monoMedium(float size) {
        return derive("plex-mono-medium", size);
    }

    private static Font derive(String key, float size) {
        // A single, central adjustment point -- every public method above
        // (headerBold, body, mono, etc.) funnels through here, so bumping
        // the size once here applies everywhere consistently, including
        // to the plain-system-font fallback path below, rather than
        // needing every call site across the app to be found and edited
        // individually (and risking missing one).
        size += 2f;

        Font base = loaded.get(key);
        if (base == null) {
            // Bundled font failed to load -- fall back to a real, always-
            // available system font rather than throw, so the app stays
            // usable (just visually degraded) if something's wrong with
            // the packaged resources.
            return new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(size));
        }
        return base.deriveFont(size);
    }
}