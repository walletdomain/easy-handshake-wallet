package handshake.wallet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A rolling cache of extracted, name-covenant-only transaction
 * fragments -- not full blocks. Once a block is downloaded and parsed
 * (unavoidable under plain P2P primitives, see this whole design
 * discussion), everything that isn't a name covenant is discarded
 * immediately; only a small fragment (covenant type, nameHash, value,
 * txid/output index, height, address) is kept per relevant transaction.
 *
 * Window size is exactly WINDOW_BLOCKS = openPeriod + biddingPeriod +
 * revealPeriod = 37 + 720 + 1440 = 2197 -- not an approximation. That's
 * the real maximum lifetime of any name still in an active, trackable
 * auction state (the same constants already verified against real hsd
 * source and already used in BlockProcessor/CovenantValidator on the
 * node side). A cache exactly this size guarantees any name eligible to
 * be watched at all is already fully covered the moment it's added to
 * the watch list, with no gap and no special-casing for "how far back
 * does this particular name's own history go".
 *
 * Storage cost is deliberately left unmeasured here -- see this
 * project's own design discussion: real block sizes vary enough (up to
 * ~20x between quiet and busy stretches, directly measured earlier this
 * session) that an estimate wouldn't be worth much; the plan is to
 * measure this for real once the wallet is actually running.
 */
public class WalletCovenantCache {

    public static final int WINDOW_BLOCKS = 37 + 720 + 1440; // 2197

    public static class CachedCovenantTx {
        public int covenantType;
        public byte[] nameHash;
        public long value;
        public byte[] txid;
        public int outputIndex;
        public int height;
        public byte[] addrHash;
    }

    // Height -> fragments seen in that block, for windowed eviction.
    private final TreeMap<Integer, List<CachedCovenantTx>> byHeight = new TreeMap<>();
    // nameHash (hex) -> fragments, for fast per-name lookup.
    private final Map<String, List<CachedCovenantTx>> byNameHash = new HashMap<>();

    private int highestHeightSeen = -1;

    /** Scans one already-downloaded, full raw block, keeping only its
     *  name-covenant transactions (COV_NONE and COV_CLAIM excluded --
     *  neither is a name-lifecycle event this wallet needs to track),
     *  then evicts anything now outside the rolling window. */
    public void ingestBlock(byte[] rawBlock, int height) {
        List<TxParser.ParsedTx> txs = WalletBlockParser.parseBlockTxs(rawBlock);
        List<CachedCovenantTx> fragments = new ArrayList<>();

        for (TxParser.ParsedTx tx : txs) {
            byte[] txid = null;
            for (int i = 0; i < tx.outputs.size(); i++) {
                TxParser.Output out = tx.outputs.get(i);
                int type = out.covenant.type;
                if (type == TxParser.COV_NONE || type == TxParser.COV_CLAIM) continue;
                if (out.covenant.items.isEmpty()) continue;

                if (txid == null) {
                    txid = Blake2b.hash256(java.util.Arrays.copyOf(tx.raw, tx.baseSize));
                }

                CachedCovenantTx frag = new CachedCovenantTx();
                frag.covenantType = type;
                frag.nameHash = out.covenant.items.get(0).data;
                frag.value = out.value;
                frag.txid = txid;
                frag.outputIndex = i;
                frag.height = height;
                frag.addrHash = out.addrHash;
                fragments.add(frag);

                byNameHash.computeIfAbsent(hex(frag.nameHash), k -> new ArrayList<>()).add(frag);
            }
        }

        byHeight.put(height, fragments);
        if (height > highestHeightSeen) highestHeightSeen = height;
        evictOutsideWindow();
    }

    private void evictOutsideWindow() {
        int cutoff = highestHeightSeen - WINDOW_BLOCKS;
        while (!byHeight.isEmpty() && byHeight.firstKey() < cutoff) {
            Map.Entry<Integer, List<CachedCovenantTx>> oldest = byHeight.pollFirstEntry();
            for (CachedCovenantTx frag : oldest.getValue()) {
                String key = hex(frag.nameHash);
                List<CachedCovenantTx> list = byNameHash.get(key);
                if (list != null) {
                    list.remove(frag);
                    if (list.isEmpty()) byNameHash.remove(key);
                }
            }
        }
    }

    /** Every cached covenant fragment for a given name, oldest first --
     *  everything currently known within the rolling window, regardless
     *  of covenant type (BIDs and REVEALs both come back through here;
     *  callers filter by covenantType as needed). */
    public List<CachedCovenantTx> getFragmentsForName(byte[] nameHash) {
        List<CachedCovenantTx> list = byNameHash.get(hex(nameHash));
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    public boolean hasBlock(int height) {
        return byHeight.containsKey(height);
    }

    public int lowestCachedHeight() {
        return byHeight.isEmpty() ? -1 : byHeight.firstKey();
    }

    public int highestCachedHeight() {
        return highestHeightSeen;
    }

    public int totalFragmentCount() {
        int total = 0;
        for (List<CachedCovenantTx> l : byHeight.values()) total += l.size();
        return total;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}