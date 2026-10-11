package handshake.wallet;

import java.math.BigInteger;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * BIP-32 hierarchical deterministic key derivation. Like Bip39, this is
 * a universal, coin-agnostic standard -- confirmed directly against
 * real hsd's own lib/hd/private.js, which uses the exact same,
 * unmodified "Bitcoin seed" HMAC constant BIP32 itself specifies (no
 * Handshake-specific deviation here, unlike the merkle tree or address
 * hashing algorithm elsewhere in this project).
 * <p>
 * This class covers the PRIVATE key derivation path (hardened and
 * normal) -- see HDPublicKey for the separate, public-only ("neutered")
 * counterpart, reachable from any HDKey via neuter(). Kept as two
 * distinct classes rather than one with a nullable privateKey field,
 * deliberately: every existing method and call site here can keep
 * assuming privateKey is always present, with zero risk of a lock/
 * unlock change elsewhere in the wallet silently making that assumption
 * false somewhere it wasn't audited for.
 */
public class HDKey {

    private static final byte[] SEED_SALT = "Bitcoin seed".getBytes(); // confirmed verbatim from hsd's private.js

    public final byte[] privateKey;   // 32 bytes
    public final byte[] chainCode;    // 32 bytes
    public final int depth;
    public final int childIndex;
    public final byte[] parentFingerprint; // 4 bytes

    private HDKey(byte[] privateKey, byte[] chainCode, int depth, int childIndex, byte[] parentFingerprint) {
        this.privateKey = privateKey;
        this.chainCode = chainCode;
        this.depth = depth;
        this.childIndex = childIndex;
        this.parentFingerprint = parentFingerprint;
    }

    /** Derives the master key from a BIP-39 seed: HMAC-SHA512(key=
     *  "Bitcoin seed", data=seed), split into IL (private key) and IR
     *  (chain code). */
    public static HDKey masterFromSeed(byte[] seed) {
        byte[] i = hmacSha512(SEED_SALT, seed);
        byte[] il = Arrays.copyOfRange(i, 0, 32);
        byte[] ir = Arrays.copyOfRange(i, 32, 64);
        BigInteger ilInt = new BigInteger(1, il);
        if (ilInt.equals(BigInteger.ZERO) || ilInt.compareTo(Secp256k1.N) >= 0) {
            throw new IllegalStateException("Invalid master key from seed (astronomically unlikely) -- use different entropy");
        }
        return new HDKey(il, ir, 0, 0, new byte[4]);
    }

    /** Derives a child key. index should already include the hardened
     *  bit (0x80000000) if hardened derivation is wanted -- callers
     *  typically use deriveHardened()/derive() below instead of calling
     *  this directly. */
    public HDKey deriveChild(int index) {
        boolean hardened = (index & 0x80000000) != 0;
        byte[] data = new byte[37];
        if (hardened) {
            // 0x00 || privateKey || index(4 bytes BE)
            System.arraycopy(privateKey, 0, data, 1, 32);
        } else {
            // serP(point(privateKey)) || index(4 bytes BE)
            byte[] pub = Secp256k1.compressedPublicKey(privateKey);
            System.arraycopy(pub, 0, data, 0, 33);
        }
        data[33] = (byte) (index >>> 24);
        data[34] = (byte) (index >>> 16);
        data[35] = (byte) (index >>> 8);
        data[36] = (byte) index;

        byte[] i = hmacSha512(chainCode, hardened
                ? data
                : data); // same buffer either way; layout above already differs by branch
        byte[] il = Arrays.copyOfRange(i, 0, 32);
        byte[] ir = Arrays.copyOfRange(i, 32, 64);

        BigInteger ilInt = new BigInteger(1, il);
        if (ilInt.compareTo(Secp256k1.N) >= 0) {
            throw new IllegalStateException("Invalid child key at index " + index + " (astronomically unlikely) -- skip to next index");
        }
        BigInteger parentInt = new BigInteger(1, privateKey);
        BigInteger childInt = ilInt.add(parentInt).mod(Secp256k1.N);
        if (childInt.equals(BigInteger.ZERO)) {
            throw new IllegalStateException("Invalid child key at index " + index + " (astronomically unlikely) -- skip to next index");
        }

        byte[] childKeyBytes = to32Bytes(childInt);
        byte[] fingerprint = fingerprintOf(privateKey);
        return new HDKey(childKeyBytes, ir, depth + 1, index, fingerprint);
    }

    public HDKey deriveHardened(int index) {
        return deriveChild(index | 0x80000000);
    }

    public HDKey derive(int index) {
        return deriveChild(index);
    }

    /**
     * Returns the public-only ("neutered") counterpart of this key --
     * same public key, chain code, depth, index, and fingerprint, just
     * never carrying the private key at all. Always safe to compute
     * for any key, at any depth: deriving a public key FROM a private
     * one is a one-way, information-losing operation, so neutering
     * can't leak anything neutering itself didn't already reveal.
     * <p>
     * This is what lets a session's own long-lived address-derivation
     * needs (WalletAddressManager, which has to keep extending the
     * watched-address gap limit during ordinary background sync, lock
     * state or not) stay completely independent of whether the
     * SENSITIVE, signing-capable key this was neutered from has since
     * been destroy()ed -- see WalletSession's own lock()/unlock() for
     * the actual security boundary this makes possible.
     */
    public HDPublicKey neuter() {
        // Arrays.copyOf(chainCode, ...), NOT chainCode directly -- a
        // real bug, caught by an actual end-to-end lock/unlock test,
        // not found by inspection: without this copy, the returned
        // HDPublicKey's chainCode is the SAME array reference as this
        // key's own, so a later destroy() (which zeroes chainCode in
        // place, exactly because `final` only blocks reassigning a
        // field, not mutating its contents) would silently corrupt the
        // "independent" neutered key too, defeating the entire point
        // of neutering it in the first place. publicKey() doesn't need
        // the same treatment: it already computes a fresh byte array
        // on every call rather than returning a stored reference.
        return new HDPublicKey(publicKey(), Arrays.copyOf(chainCode, chainCode.length),
                depth, childIndex, parentFingerprint.clone());
    }

    /**
     * Actively zeroes this key's own sensitive bytes in place -- the
     * same defensive pattern already used elsewhere in this project
     * (UnlockWalletPanel's own Arrays.fill(password, '\0') on its
     * password char[]). Works directly on these `final` fields without
     * needing to reassign them: `final` only prevents rebinding the
     * field to a different array, it says nothing about mutating the
     * array's own contents, which is exactly what this does. Once
     * called, this HDKey instance is permanently unusable (privateKey
     * is now all-zero, an invalid scalar) -- it exists only to be
     * discarded right after, not reused.
     */
    public void destroy() {
        Arrays.fill(privateKey, (byte) 0);
        Arrays.fill(chainCode, (byte) 0);
    }

    /** Derives along a full path given as plain integers with a
     *  parallel hardened[] flag array, e.g. deriveHandshakeAccount()
     *  below for the concrete m/44'/5353'/account' path this wallet
     *  actually uses. */
    public HDKey derivePath(int[] indexes, boolean[] hardenedFlags) {
        HDKey key = this;
        for (int i = 0; i < indexes.length; i++) {
            key = hardenedFlags[i] ? key.deriveHardened(indexes[i]) : key.derive(indexes[i]);
        }
        return key;
    }

    public byte[] publicKey() {
        return Secp256k1.compressedPublicKey(privateKey);
    }

    private static byte[] fingerprintOf(byte[] parentPrivateKey) {
        byte[] pub = Secp256k1.compressedPublicKey(parentPrivateKey);
        byte[] hash160 = Blake2b.hash160(pub);
        // BIP32's own fingerprint is HASH160 = RIPEMD160(SHA256(pubkey)),
        // first 4 bytes -- NOT the same primitive as Handshake's own
        // Blake2b-based address hash160. Fingerprints here are purely
        // an internal bookkeeping aid (parent-child linkage within this
        // wallet's own derivation, never transmitted or compared
        // against anything external), so using Blake2b consistently
        // with the rest of this project is a deliberate, harmless
        // simplification rather than a compatibility requirement.
        return Arrays.copyOfRange(hash160, 0, 4);
    }

    // Package-private (not private) -- HDPublicKey, in the same
    // package, reuses these two directly for its own CKDpub derivation
    // rather than duplicating identical HMAC/byte-conversion logic in
    // a second file.
    static byte[] to32Bytes(BigInteger n) {
        byte[] raw = n.toByteArray();
        if (raw.length == 32) return raw;
        byte[] result = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, result, 0, 32);
        } else {
            System.arraycopy(raw, 0, result, 32 - raw.length, raw.length);
        }
        return result;
    }

    static byte[] hmacSha512(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key, "HmacSHA512"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA512 failed", e); // HmacSHA512 is always available in a standard JDK
        }
    }
}