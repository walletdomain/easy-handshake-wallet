package handshake.wallet.gui.theme;

import com.formdev.flatlaf.FlatDarkLaf;

/**
 * The wallet's own FlatLaf theme -- "Slate & Silver", the palette
 * settled on after comparing real HTML mockups built from colors
 * directly extracted (not approximated) from the project's own icon.
 *
 * Follows FlatLaf's own documented custom-theme mechanism exactly: a
 * subclass of FlatDarkLaf paired with a same-named .properties file in
 * this same package (EasyHandshakeWalletTheme.properties), which
 * FlatLaf loads automatically and merges over the base FlatDarkLaf
 * defaults. Only the properties actually being changed are set there,
 * not a full copy of FlatDarkLaf's own file -- per FlatLaf's own
 * documentation, copying everything makes future FlatLaf upgrades
 * needlessly fragile.
 */
public class EasyHandshakeWalletTheme extends FlatDarkLaf {

    public static boolean setup() {
        return setup(new EasyHandshakeWalletTheme());
    }

    @Override
    public String getName() {
        return "Easy Handshake Wallet";
    }
}