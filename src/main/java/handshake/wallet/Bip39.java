package handshake.wallet;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * BIP-39 mnemonic generation, validation, and mnemonic-to-seed
 * conversion. A universal, coin-agnostic standard -- nothing here is
 * Handshake-specific (that starts at HDKey's derivation path and
 * address generation). Implemented directly from the BIP-39 spec
 * (github.com/bitcoin/bips/blob/master/bip-0039.mediawiki) and checked
 * against the official, widely-used trezor/python-mnemonic test
 * vectors covering both 12-word (128-bit entropy) and 24-word
 * (256-bit entropy) mnemonics.
 */
public final class Bip39 {

    private static final Map<String, Integer> WORD_INDEX = new HashMap<>();
    static {
        for (int i = 0; i < Bip39WordList.WORDS.length; i++) {
            WORD_INDEX.put(Bip39WordList.WORDS[i], i);
        }
    }

    private Bip39() {}

    /** Generates a new mnemonic with the given entropy size in bits.
     *  This wallet offers new users 24 words (256 bits) by default --
     *  matching real hsd's own default, per the community-confirmed
     *  derivation example -- but 12-word generation is also supported
     *  for compatibility with wallets imported from elsewhere. */
    public static String generate(int entropyBits) {
        if (entropyBits != 128 && entropyBits != 160 && entropyBits != 192
                && entropyBits != 224 && entropyBits != 256) {
            throw new IllegalArgumentException("Entropy bits must be one of 128/160/192/224/256, got " + entropyBits);
        }
        byte[] entropy = new byte[entropyBits / 8];
        new SecureRandom().nextBytes(entropy);
        return entropyToMnemonic(entropy);
    }

    /** Encodes raw entropy into its mnemonic sentence, per BIP-39
     *  section "Generating the mnemonic": entropy bits + a checksum
     *  (the first ENT/32 bits of SHA256(entropy)) appended, then split
     *  into 11-bit groups, each an index into the wordlist. */
    public static String entropyToMnemonic(byte[] entropy) {
        int entBits = entropy.length * 8;
        int csBits = entBits / 32;
        byte[] hash = sha256(entropy);

        // Total bits = entBits + csBits; build a single bit-string
        // (entropy followed by the first csBits of the hash) rather
        // than juggle byte boundaries directly, since csBits isn't
        // generally byte-aligned (e.g. 4 bits for a 12-word mnemonic).
        StringBuilder bits = new StringBuilder();
        for (byte b : entropy) {
            for (int i = 7; i >= 0; i--) bits.append((b >> i) & 1);
        }
        for (int i = 0; i < csBits; i++) {
            int byteIndex = i / 8;
            int bitIndex = 7 - (i % 8);
            bits.append((hash[byteIndex] >> bitIndex) & 1);
        }

        int wordCount = bits.length() / 11;
        List<String> words = new ArrayList<>(wordCount);
        for (int i = 0; i < wordCount; i++) {
            String group = bits.substring(i * 11, i * 11 + 11);
            int index = Integer.parseInt(group, 2);
            words.add(Bip39WordList.WORDS[index]);
        }
        return String.join(" ", words);
    }

    /** Validates a mnemonic's word count, that every word is in the
     *  wordlist, and that its embedded checksum matches -- catches
     *  typos and mis-transcriptions the same way real wallets do,
     *  rather than silently accepting anything and deriving a
     *  different, wrong seed. */
    public static boolean isValid(String mnemonic) {
        try {
            String[] words = normalize(mnemonic).split(" ");
            if (words.length != 12 && words.length != 15 && words.length != 18
                    && words.length != 21 && words.length != 24) {
                return false;
            }
            StringBuilder bits = new StringBuilder();
            for (String w : words) {
                Integer idx = WORD_INDEX.get(w);
                if (idx == null) return false;
                String b = Integer.toBinaryString(idx);
                while (b.length() < 11) b = "0" + b;
                bits.append(b);
            }
            int totalBits = bits.length();
            int csBits = totalBits / 33; // ENT = 32*CS, total = ENT+CS = 33*CS
            int entBits = totalBits - csBits;

            byte[] entropy = new byte[entBits / 8];
            for (int i = 0; i < entropy.length; i++) {
                entropy[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
            }
            byte[] hash = sha256(entropy);
            for (int i = 0; i < csBits; i++) {
                int byteIndex = i / 8;
                int bitIndex = 7 - (i % 8);
                int expectedBit = (hash[byteIndex] >> bitIndex) & 1;
                int actualBit = bits.charAt(entBits + i) - '0';
                if (expectedBit != actualBit) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Converts a mnemonic (plus optional passphrase, empty string if
     *  none) into a 64-byte seed via PBKDF2-HMAC-SHA512, 2048
     *  iterations, salt = "mnemonic" + passphrase -- per BIP-39's own
     *  spec exactly, independent of wordlist/checksum validity (BIP-39
     *  deliberately allows deriving A seed from any wordlist-valid text
     *  even with a bad checksum, for plausible-deniability wallets;
     *  callers that want to reject typos should call isValid() first,
     *  which this wallet's import flow does). Both mnemonic and
     *  passphrase are NFKD-normalized first, per spec, since the
     *  seed changes if normalization is skipped and the input contains
     *  any non-ASCII characters. */
    public static byte[] mnemonicToSeed(String mnemonic, String passphrase) {
        String normalizedMnemonic = normalize(mnemonic);
        String normalizedPassphrase = Normalizer.normalize(
                passphrase == null ? "" : passphrase, Normalizer.Form.NFKD);
        String salt = "mnemonic" + normalizedPassphrase;
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            PBEKeySpec spec = new PBEKeySpec(
                    normalizedMnemonic.toCharArray(), salt.getBytes("UTF-8"), 2048, 512);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2 seed derivation failed", e);
        }
    }

    private static String normalize(String mnemonic) {
        return Normalizer.normalize(mnemonic.trim(), Normalizer.Form.NFKD).replaceAll("\\s+", " ");
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e); // SHA-256 is always available in a standard JDK
        }
    }
}