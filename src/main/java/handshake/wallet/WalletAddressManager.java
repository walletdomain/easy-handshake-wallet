package handshake.wallet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages which addresses a wallet account is currently watching,
 * using the standard "gap limit" convention essentially every HD
 * wallet follows (a universal convention, not Handshake-specific):
 * always keep at least GAP_LIMIT consecutive, never-used addresses
 * derived beyond the highest index that's ever actually received a
 * transaction, on both the external (receive) and internal (change)
 * chains independently. This is what lets a freshly-imported wallet
 * (seed only, no other metadata) correctly rediscover its own full
 * transaction history: watch the first batch, and every time a
 * previously-unused watched address turns out to have activity, extend
 * the watched set further so the gap in front of the new highest-used
 * index is refilled.
 */
public class WalletAddressManager {

    private static final int GAP_LIMIT = 100;

    // HDPublicKey, not HDKey -- this class only ever needs to derive
    // PUBLIC addresses to watch (see HandshakeKeyPath's own top
    // comment on why change/index are non-hardened specifically to
    // make this possible), never to sign anything. Deliberately: this
    // keeps address-gap-limit extension -- which happens during
    // ordinary background sync, whenever new activity is found --
    // completely independent of whether WalletSession's own,
    // SIGNING-capable accountKey has been destroy()ed by a lock. See
    // WalletSession's own lock()/unlock() for the actual security
    // boundary this separation makes possible.
    private final HDPublicKey accountKey;
    private final Map<Integer, List<byte[]>> addressHashesByChain = new HashMap<>(); // chain -> ordered list, index = derivation index
    private final Map<Integer, Integer> highestUsedIndex = new HashMap<>(); // chain -> highest index with known activity, -1 if none

    public WalletAddressManager(HDPublicKey accountKey) {
        this.accountKey = accountKey;
        addressHashesByChain.put(HandshakeKeyPath.CHANGE_EXTERNAL, new ArrayList<>());
        addressHashesByChain.put(HandshakeKeyPath.CHANGE_INTERNAL, new ArrayList<>());
        highestUsedIndex.put(HandshakeKeyPath.CHANGE_EXTERNAL, -1);
        highestUsedIndex.put(HandshakeKeyPath.CHANGE_INTERNAL, -1);
        extendToGapLimit(HandshakeKeyPath.CHANGE_EXTERNAL);
        extendToGapLimit(HandshakeKeyPath.CHANGE_INTERNAL);
    }

    /** Derives and appends addresses on the given chain until at least
     *  GAP_LIMIT unused ones exist beyond the highest known used index. */
    private void extendToGapLimit(int chain) {
        List<byte[]> hashes = addressHashesByChain.get(chain);
        int needed = highestUsedIndex.get(chain) + 1 + GAP_LIMIT;
        while (hashes.size() < needed) {
            int index = hashes.size();
            HDPublicKey addressKey = HandshakeKeyPath.deriveAddressKey(accountKey, chain, index);
            hashes.add(Blake2b.hash160(addressKey.publicKey));
        }
    }

    /** Marks an address index as used (found real, confirmed activity
     *  for it), extending the watched set on that chain further if this
     *  moves the gap forward. Safe to call repeatedly / out of order --
     *  only ever moves highestUsedIndex forward, never back. */
    public void markUsed(int chain, int index) {
        int current = highestUsedIndex.get(chain);
        if (index > current) {
            highestUsedIndex.put(chain, index);
            extendToGapLimit(chain);
        }
    }

    /** Returns every address hash currently being watched on both
     *  chains -- what the wallet's bloom filter should be built from. */
    public List<byte[]> allWatchedAddressHashes() {
        List<byte[]> all = new ArrayList<>();
        all.addAll(addressHashesByChain.get(HandshakeKeyPath.CHANGE_EXTERNAL));
        all.addAll(addressHashesByChain.get(HandshakeKeyPath.CHANGE_INTERNAL));
        return all;
    }

    /** Looks up which (chain, index) a given address hash corresponds
     *  to, or null if it isn't currently watched -- used to translate a
     *  freshly-discovered UTXO's paid-to address back into "mark this
     *  index used" via markUsed(). */
    public int[] findChainAndIndex(byte[] addrHash) {
        for (int chain : new int[]{HandshakeKeyPath.CHANGE_EXTERNAL, HandshakeKeyPath.CHANGE_INTERNAL}) {
            List<byte[]> hashes = addressHashesByChain.get(chain);
            for (int i = 0; i < hashes.size(); i++) {
                if (java.util.Arrays.equals(hashes.get(i), addrHash)) {
                    return new int[]{chain, i};
                }
            }
        }
        return null;
    }

    /** Convenience: processes a matched transaction against both a UTXO
     *  set and this address manager together -- applying the balance
     *  update AND marking any newly-discovered address activity used
     *  (extending the watched set / gap limit as needed) in one call. */
    public void processTransaction(WalletUtxoSet utxoSet, TxParser.ParsedTx tx, int height) {
        utxoSet.applyTransaction(tx, height);
        for (TxParser.Output output : tx.outputs) {
            if (output.addrHash == null) continue;
            int[] chainAndIndex = findChainAndIndex(output.addrHash);
            if (chainAndIndex != null) {
                markUsed(chainAndIndex[0], chainAndIndex[1]);
            }
        }
    }

    public String addressAt(int chain, int index) {
        return HandshakeKeyPath.toAddress(HandshakeKeyPath.deriveAddressKey(accountKey, chain, index));
    }

    public int watchedCount(int chain) {
        return addressHashesByChain.get(chain).size();
    }

    public int highestUsed(int chain) {
        return highestUsedIndex.get(chain);
    }
}