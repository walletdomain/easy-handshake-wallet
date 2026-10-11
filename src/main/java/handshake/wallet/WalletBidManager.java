package handshake.wallet;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages the blind/nonce lifecycle for name auctions: computing a
 * bid's blind commitment, and tracking the (value, nonce) pair each
 * pending bid needs at REVEAL time.
 *
 * blind = Blake2b(U64(value) || nonce) -- confirmed directly from real
 * hsd's own rules.blind() (lib/covenants/rules.js), not assumed: a
 * 40-byte buffer (8-byte value, 32-byte nonce), Blake2b digest (32-byte
 * output, the library's own default, not Blake2b-160 like address
 * hashing elsewhere in this project -- a real, confirmed difference
 * worth being explicit about given how many "which Blake2b variant"
 * mismatches this whole project has already found).
 *
 * Nonce generation: deterministic, derived from this wallet's own
 * account private key plus the bid's own public data (name, bid
 * address, value) -- NOT hsd's own internal algorithm, and that's
 * fine: confirmed directly from real hsd's own documentation (HIP-8)
 * that the nonce has zero consensus restriction beyond being 32 bytes;
 * hsw's specific derivation is a wallet-internal convenience only,
 * never checked by anyone else. What matters is that THIS wallet can
 * always reproduce it from its own seed plus public data, without
 * needing to store anything extra for the nonce specifically.
 *
 * The nonce alone is not the full recoverability story, though. Real
 * hsd's own HIP-8 identifies a harder problem this class does not yet
 * solve: even with a perfectly reproducible nonce, the bid VALUE
 * itself cannot be regenerated from a seed alone (it's arbitrary user
 * input, not derived from anything on-chain), and losing it before
 * REVEAL means the bid's locked funds are permanently forfeited --
 * REVEAL transactions are only valid for a limited window. HIP-8
 * proposes storing an encrypted copy of the bid value on-chain
 * specifically to close this gap, but it's still a draft, not a
 * deployed standard, and deliberately not implemented here yet. For
 * now, correctness depends entirely on this wallet's own local storage
 * of pending bids surviving until REVEAL -- worth flagging explicitly,
 * not something to gloss over given the stakes (permanent, unrecoverable
 * loss of the bid amount).
 */
public class WalletBidManager {

    public static class PendingBid {
        public byte[] nameHash;
        public String name;
        public long value;      // the real, secret bid amount
        public long lockup;     // the BID output's own value -- >= value, may be higher to obscure the real bid
        public byte[] nonce;
        public byte[] blind;
        public int openHeight;
        public byte[] bidAddrHash;
        public byte[] bidTxid;
        public int bidOutputIndex;
    }

    private final Map<String, PendingBid> pendingByNameHash = new HashMap<>();

    /** Computes blind = Blake2b(U64(value) || nonce) -- see this
     *  class's own comment for the real, confirmed formula and hash
     *  variant. */
    public static byte[] computeBlind(long value, byte[] nonce) {
        if (nonce.length != 32) throw new IllegalArgumentException("nonce must be 32 bytes");
        byte[] preimage = new byte[40];
        writeLE64(preimage, 0, value);
        System.arraycopy(nonce, 0, preimage, 8, 32);
        return Blake2b.hash(preimage, 32);
    }

    /** Deterministically derives a nonce from this wallet's own account
     *  key plus the bid's public data -- reproducible from seed alone,
     *  secret to anyone without it, unique per (name, bid address,
     *  value). See this class's own top comment for why this doesn't
     *  need to match hsd's own internal algorithm. */
    public static byte[] deriveNonce(HDKey accountKey, byte[] nameHash, byte[] bidAddrHash, long value) {
        byte[] valueBytes = new byte[8];
        writeLE64(valueBytes, 0, value);
        byte[] preimage = new byte[32 + 32 + 20 + 8];
        int pos = 0;
        System.arraycopy(accountKey.privateKey, 0, preimage, pos, 32); pos += 32;
        System.arraycopy(nameHash, 0, preimage, pos, 32); pos += 32;
        System.arraycopy(bidAddrHash, 0, preimage, pos, 20); pos += 20;
        System.arraycopy(valueBytes, 0, preimage, pos, 8);
        return Blake2b.hash(preimage, 32);
    }

    /** Builds a full PendingBid record -- computing the deterministic
     *  nonce and the resulting blind -- and stores it for later REVEAL.
     *  Does NOT build or sign the actual BID transaction; see
     *  WalletCovenantTxBuilder for that, which calls this first to get
     *  the blind it needs to embed in the covenant. */
    public PendingBid createBid(HDKey accountKey, String name, byte[] nameHash, int openHeight,
                                long value, long lockup, byte[] bidAddrHash) {
        if (lockup < value) {
            throw new IllegalArgumentException("lockup (" + lockup + ") must be >= the real bid value (" + value
                    + ") -- real hsd's own verifyCovenants() permanently forfeits the bid if the lockup is ever lower");
        }
        byte[] nonce = deriveNonce(accountKey, nameHash, bidAddrHash, value);
        byte[] blind = computeBlind(value, nonce);

        PendingBid bid = new PendingBid();
        bid.nameHash = nameHash;
        bid.name = name;
        bid.value = value;
        bid.lockup = lockup;
        bid.nonce = nonce;
        bid.blind = blind;
        bid.openHeight = openHeight;
        bid.bidAddrHash = bidAddrHash;

        pendingByNameHash.put(hex(nameHash), bid);
        return bid;
    }

    /** Records the bid transaction's own txid/output index, once known
     *  (after building and signing it) -- REVEAL needs to spend this
     *  exact outpoint. */
    public void recordBidOutpoint(byte[] nameHash, byte[] bidTxid, int bidOutputIndex) {
        PendingBid bid = pendingByNameHash.get(hex(nameHash));
        if (bid != null) {
            bid.bidTxid = bidTxid;
            bid.bidOutputIndex = bidOutputIndex;
        }
    }

    public PendingBid getPendingBid(byte[] nameHash) {
        return pendingByNameHash.get(hex(nameHash));
    }

    public void removePendingBid(byte[] nameHash) {
        pendingByNameHash.remove(hex(nameHash));
    }

    /** Directly inserts an already-constructed PendingBid (nonce and
     *  blind already computed) -- used when restoring from persistent
     *  storage, where re-deriving the nonce would be both unnecessary
     *  and, if the account key somehow differed, silently wrong. */
    public void restorePendingBid(PendingBid bid) {
        pendingByNameHash.put(hex(bid.nameHash), bid);
    }

    private static void writeLE64(byte[] b, int pos, long v) {
        for (int i = 0; i < 8; i++) b[pos + i] = (byte) (v >>> (i * 8));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}