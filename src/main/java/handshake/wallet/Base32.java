package handshake.wallet;

/**
 * Base32 encode/decode for Handshake node identity strings
 * ("<base32pubkey>@<ip>:<port>"). Extracted from the node project's own
 * NodeIdentity class -- the encoding/decoding logic itself, not the
 * broader identity-file-persistence machinery around it, which this
 * wallet doesn't need (see WalletPeerConnection's own comment on why
 * the wallet generates a fresh, ephemeral identity per session instead
 * of a persistent one).
 */
public final class Base32 {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";

    private Base32() {}

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int bits = 0, value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((value >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((value << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    public static byte[] decode(String s) {
        s = s.toLowerCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bits = 0, value = 0;
        for (char c : s.toCharArray()) {
            int idx = ALPHABET.indexOf(c);
            if (idx < 0) continue; // skip any non-alphabet character rather than fail
            value = (value << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                out.write((value >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}