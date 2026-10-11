package handshake.wallet.gui.theme;

/**
 * Maps a currency code (as stored in WalletStorage's own wallets table)
 * to its display string. A plain switch, not backed by any enum or
 * registry -- deliberately small and easy to extend by just adding a
 * case, matching this whole multi-currency effort's own guiding idea:
 * the infrastructure should be in place now, without needing to be
 * elaborate before there's a second currency to actually justify it.
 */
public final class CurrencyDisplay {

    private CurrencyDisplay() {}

    public static String name(String currencyCode) {
        return switch (currencyCode) {
            case "HNS" -> "Handshake (HNS)";
            case "BTC" -> "Bitcoin (BTC)";
            // Falls back to the raw code rather than throwing -- an
            // unrecognized currency (a newer build's data opened by an
            // older one, say) should still show something sensible in
            // the dropdown, not break it.
            default -> currencyCode;
        };
    }
}