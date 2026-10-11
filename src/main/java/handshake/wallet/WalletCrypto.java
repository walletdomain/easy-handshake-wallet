package handshake.wallet;

import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Field-level encryption for the wallet's own highest-stakes stored
 * data (the seed, and a pending bid's real value + nonce -- see
 * WalletBidManager's own comment on why losing either of those before
 * REVEAL means the bid's entire lockup is permanently forfeited) --
 * deliberately NOT full-database encryption, per this project's own
 * earlier design discussion: the real threat this defends against is
 * someone getting the file and being able to spend funds or reveal
 * secrets, which encrypting just the crown-jewel fields already fully
 * addresses, without pulling in a separate native dependency (SQLCipher
 * or similar) purely to also hide address/balance/history data that
 * isn't the actual risk.
 *
 * PBKDF2WithHmacSHA512 for turning a passphrase into a key -- the exact
 * same algorithm (and same JDK-native, zero-extra-dependency
 * javax.crypto.SecretKeyFactory API) already used and verified
 * elsewhere in this project for BIP-39's own seed derivation, reused
 * here for a different purpose rather than introducing a second KDF.
 * AES-256-GCM for the actual encryption -- authenticated encryption,
 * so a wrong passphrase (or any tampering) fails verification outright
 * rather than silently decrypting to garbage bytes that might look
 * superficially valid.
 *
 * A null/empty passphrase is allowed here (still runs through the same
 * PBKDF2+AES-GCM path, just with a weak, empty-string-derived key)
 * rather than a separate "no encryption" code path -- keeps the stored
 * format uniform regardless of whether a real passphrase was set, per
 * this project's own earlier decision that null is optional but
 * discouraged, not a fundamentally different storage mode. Confirming a
 * passphrase was typed twice and matches is the caller's own
 * responsibility (eventually the GUI's) -- this class only needs a
 * single passphrase string per call.
 */
public final class WalletCrypto {

    private static final int PBKDF2_ITERATIONS = 210_000; // OWASP's current (2023+) minimum recommendation for PBKDF2-HMAC-SHA512
    private static final int KEY_LENGTH_BITS = 256;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int SALT_LENGTH = 16;

    private WalletCrypto() {}

    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private static byte[] deriveKey(String passphrase, byte[] salt) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            PBEKeySpec spec = new PBEKeySpec(
                    (passphrase == null ? "" : passphrase).toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }

    /** Encrypts plaintext under a passphrase and a given (caller-owned,
     *  persisted) salt. Output format: iv(12) || ciphertext+authTag --
     *  self-contained given the salt separately, so a caller only needs
     *  to store the salt once per wallet and this blob per field. */
    public static byte[] encrypt(byte[] plaintext, String passphrase, byte[] salt) {
        try {
            byte[] key = deriveKey(passphrase, salt);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /** Decrypts a blob produced by encrypt(). Throws (does not return
     *  null or garbage) if the passphrase is wrong or the data was
     *  tampered with -- GCM's own authentication tag check fails
     *  outright in either case, which callers should treat as "wrong
     *  passphrase", not attempt to recover from. */
    public static byte[] decrypt(byte[] encrypted, String passphrase, byte[] salt) throws WrongPassphraseException {
        try {
            byte[] key = deriveKey(passphrase, salt);
            byte[] iv = Arrays.copyOfRange(encrypted, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(encrypted, GCM_IV_LENGTH, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new WrongPassphraseException();
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public static class WrongPassphraseException extends Exception {
        public WrongPassphraseException() { super("Incorrect passphrase, or the stored data has been tampered with"); }
    }
}