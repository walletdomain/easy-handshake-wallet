package handshake.wallet;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * BIP-32 public-only ("neutered") key derivation -- the counterpart to
 * HDKey, reachable from any HDKey via neuter(), that carries a public
 * key and chain code but never a private key at all.
 * <p>
 * Only NORMAL (non-hardened) child derivation is possible from a
 * public-only key -- this is intentional in BIP32, not a limitation of
 * this implementation: hardened derivation's whole point is that a
 * leaked child private key, combined with the parent's public key,
 * must NOT be enough to compromise the rest of the subtree, which
 * means hardened derivation has to mix in the parent's actual private
 * key. HandshakeKeyPath's own account-level path (m/44'/5353'/account')
 * is entirely hardened, with only the change/index levels below it
 * left normal -- exactly the standard BIP44 shape that makes THIS
 * class useful at all: an account's neutered key is enough to derive
 * every one of its own receive/change addresses, with no private key
 * anywhere in that computation.
 * <p>
 * The child-key formula here (K_i = point(I_L) + K_parent, real
 * elliptic-curve point addition) is DIFFERENT from HDKey.deriveChild()'s
 * private-key formula (k_i = (I_L + k_parent) mod n, modular scalar
 * addition) -- but BIP32 guarantees both must produce the exact same
 * resulting public key for the same non-hardened index, which is
 * exactly what this class was verified against: deriving a child both
 * ways (privately, then take its public key; publicly, straight from
 * the neutered parent) and confirming byte-for-byte agreement, rather
 * than trusting the math by inspection alone.
 */
public class HDPublicKey {

    public final byte[] publicKey;    // 33 bytes, compressed
    public final byte[] chainCode;    // 32 bytes
    public final int depth;
    public final int childIndex;
    public final byte[] parentFingerprint; // 4 bytes

    HDPublicKey(byte[] publicKey, byte[] chainCode, int depth, int childIndex, byte[] parentFingerprint) {
        this.publicKey = publicKey;
        this.chainCode = chainCode;
        this.depth = depth;
        this.childIndex = childIndex;
        this.parentFingerprint = parentFingerprint;
    }

    /**
     * BIP32 CKDpub: derives a normal (non-hardened) child public key.
     * <p>
     * I = HMAC-SHA512(key=chainCode, data=serP(publicKey) || ser32(index))
     * -- note this is IDENTICAL to the HMAC input HDKey.deriveChild()
     * computes for its own non-hardened case (serP(point(privateKey))
     * there is just publicKey here, already available directly rather
     * than needing to be derived) -- confirming there's genuinely only
     * one different step between the two paths, not a parallel
     * reimplementation that happens to agree.
     * <p>
     * K_i = point(I_L) + K_parent -- real EC point addition (via
     * Secp256k1.pointAdd(), the same primitive already proven correct
     * elsewhere in this project for ECDH and signature recovery), NOT
     * private derivation's modular scalar addition on private keys.
     *
     * @throws IllegalArgumentException if index is hardened (bit 31
     *         set) -- see this class's own top comment for why that's
     *         fundamentally impossible from a public-only key, not an
     *         unimplemented case.
     */
    public HDPublicKey deriveChild(int index) {
        if ((index & 0x80000000) != 0) {
            throw new IllegalArgumentException(
                    "Cannot derive a hardened child (index " + index + ") from a public-only key");
        }

        byte[] data = new byte[37];
        System.arraycopy(publicKey, 0, data, 0, 33);
        data[33] = (byte) (index >>> 24);
        data[34] = (byte) (index >>> 16);
        data[35] = (byte) (index >>> 8);
        data[36] = (byte) index;

        byte[] i = HDKey.hmacSha512(chainCode, data);
        byte[] il = Arrays.copyOfRange(i, 0, 32);
        byte[] ir = Arrays.copyOfRange(i, 32, 64);

        BigInteger ilInt = new BigInteger(1, il);
        if (ilInt.compareTo(Secp256k1.N) >= 0) {
            throw new IllegalStateException(
                    "Invalid child key at index " + index + " (astronomically unlikely) -- skip to next index");
        }

        BigInteger[] ilPoint = Secp256k1.pubKeyPoint(il);       // point(I_L) = I_L * G
        BigInteger[] parentPoint = Secp256k1.decompressPoint(publicKey);
        BigInteger[] childPoint = Secp256k1.pointAdd(ilPoint, parentPoint);
        if (childPoint == null) {
            throw new IllegalStateException(
                    "Invalid child key at index " + index + " (astronomically unlikely, point at infinity) -- skip to next index");
        }

        byte[] x = HDKey.to32Bytes(childPoint[0]);
        byte prefix = childPoint[1].testBit(0) ? (byte) 0x03 : (byte) 0x02;
        byte[] childPubKey = new byte[33];
        childPubKey[0] = prefix;
        System.arraycopy(x, 0, childPubKey, 1, 32);

        byte[] fingerprint = fingerprintOfPubkey(publicKey);
        return new HDPublicKey(childPubKey, ir, depth + 1, index, fingerprint);
    }

    /** Same fingerprint convention as HDKey.fingerprintOf() (Blake2b
     *  hash160, first 4 bytes -- an internal bookkeeping aid only, see
     *  that method's own comment on why Blake2b rather than BIP32's
     *  literal RIPEMD160(SHA256(.))), just starting directly from an
     *  already-known public key instead of deriving one from a private
     *  key first. */
    private static byte[] fingerprintOfPubkey(byte[] pubKey) {
        byte[] hash160 = Blake2b.hash160(pubKey);
        return Arrays.copyOfRange(hash160, 0, 4);
    }
}