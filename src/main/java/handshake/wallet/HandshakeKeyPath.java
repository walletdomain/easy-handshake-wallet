package handshake.wallet;

/**
 * The Handshake-specific pieces on top of the coin-agnostic Bip39/HDKey
 * layer: the m/44'/5353'/account'/change/index derivation path and
 * address generation from a derived key.
 *
 * Coin type 5353 -- confirmed directly from a working code example in
 * the Handshake community forum (handshake.community "Testnet4
 * Onboarding" thread), which shows real derivation code computing
 * "m/44'/5353'" explicitly, not assumed or guessed from a generic
 * BIP44 pattern. This is a REAL constant specific to this network,
 * unlike Bip39/HDKey's own logic, which is universal.
 *
 * Address format: Bech32(hrp="hs", version=0, Blake2b-160(pubkey)) --
 * both the "hs" mainnet HRP and the Blake2b (not Bitcoin's SHA256+
 * RIPEMD160) address-hashing algorithm were already independently
 * verified elsewhere in this project (see Bech32.java's and
 * Blake2b.java's own comments), reused here rather than re-verified,
 * since re-deriving already-established facts wastes effort without
 * adding confidence.
 */
public final class HandshakeKeyPath {

    public static final int HANDSHAKE_COIN_TYPE = 5353;
    public static final int CHANGE_EXTERNAL = 0; // receive addresses
    public static final int CHANGE_INTERNAL = 1; // change addresses

    private HandshakeKeyPath() {}

    /** Derives the account-level key: m/44'/5353'/account'. Everything
     *  below this level (change, index) is normal (non-hardened)
     *  derivation, matching standard BIP44 -- so an account's public
     *  key alone is enough to derive every receive/change address
     *  under it without the private key, the property watch-only
     *  wallets rely on (not used by this wallet yet, but worth
     *  preserving since it costs nothing to derive the standard way).
     */
    public static HDKey deriveAccount(HDKey master, int account) {
        return master
                .deriveHardened(44)
                .deriveHardened(HANDSHAKE_COIN_TYPE)
                .deriveHardened(account);
    }

    /** Derives a specific address key: account/change/index. */
    public static HDKey deriveAddressKey(HDKey accountKey, int change, int index) {
        return accountKey.derive(change).derive(index);
    }

    /** Same as deriveAddressKey(HDKey, ...) above, but from a
     *  public-only (neutered) account key -- see HDPublicKey's own
     *  class comment. Works only because change/index are both normal
     *  (non-hardened) derivation, exactly per this class's own top
     *  comment on the path shape. */
    public static HDPublicKey deriveAddressKey(HDPublicKey accountKey, int change, int index) {
        return accountKey.deriveChild(change).deriveChild(index);
    }

    /** Encodes a derived key's public key as a mainnet Handshake
     *  address: Bech32("hs", version=0, Blake2b-160(compressed pubkey)). */
    public static String toAddress(HDKey addressKey) {
        byte[] pubKey = addressKey.publicKey();
        byte[] addrHash = Blake2b.hash160(pubKey);
        return Bech32.encode("hs", 0, addrHash);
    }

    /** Same as toAddress(HDKey) above, for a public-only address key. */
    public static String toAddress(HDPublicKey addressKey) {
        byte[] addrHash = Blake2b.hash160(addressKey.publicKey);
        return Bech32.encode("hs", 0, addrHash);
    }

    /** Convenience: seed straight through to a specific address, for
     *  the common case (account 0, external chain). */
    public static String addressFromSeed(byte[] seed, int account, int change, int index) {
        HDKey master = HDKey.masterFromSeed(seed);
        HDKey accountKey = deriveAccount(master, account);
        HDKey addressKey = deriveAddressKey(accountKey, change, index);
        return toAddress(addressKey);
    }
}