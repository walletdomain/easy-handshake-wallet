package handshake.wallet;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal per-name state tracking the wallet needs to construct valid
 * covenants -- not a general name-auction state machine (that already
 * exists, verified, on the node side; see CovenantValidator/
 * BlockProcessor's own computeState()), just the handful of fields a
 * wallet needs to remember about names IT has personally interacted
 * with, discovered from its own synced transaction history.
 *
 * The single most important field here is openHeight: every covenant
 * type from BID onward carries a "height" item that consensus rules
 * require to exactly match the name's original OPEN height for its
 * entire life (confirmed directly from real hsd's own
 * verifyCovenants() -- every non-OPEN case checks
 * covenant.getU32(1) === uc.getU32(1) against the covenant being
 * spent) -- NOT the current block height, a mistake that's easy to
 * make since ordinary covenants also frequently mention "height" for
 * genuinely current-height purposes elsewhere in this project. Get
 * this wrong and every covenant after OPEN for that name is invalid.
 */
public class WalletNameState {

    public static class Entry {
        public String name;
        public byte[] nameHash;
        public int openHeight;
        public boolean registered;
        // Height of the most recent REGISTER/RENEW/FINALIZE -- the real
        // reference point a renewal deadline is computed from
        // (deadline = renewalHeight + RENEWAL_WINDOW, the same real,
        // verified 105,120-block/~2-year constant already established
        // and used in BlockProcessor/CovenantValidator on the node
        // side), NOT openHeight, which only matters for the original
        // auction.
        public int renewalHeight;
        // The current owning UTXO -- what a future RENEW/UPDATE/
        // TRANSFER/REVOKE actually needs to spend. Kept here rather
        // than re-derived each time from WalletUtxoSet, since "which
        // UTXO currently owns this specific name" isn't something
        // WalletUtxoSet tracks on its own -- it only knows about
        // amounts and addresses, not which one is a name's own current
        // covenant chain tip.
        public byte[] ownerTxid;
        public int ownerOutputIndex;
    }

    private final Map<String, Entry> byNameHash = new HashMap<>();

    public void recordOpen(String name, byte[] nameHash, int openHeight) {
        Entry e = new Entry();
        e.name = name;
        e.nameHash = nameHash;
        e.openHeight = openHeight;
        e.registered = false;
        byNameHash.put(hex(nameHash), e);
    }

    public void markRegistered(byte[] nameHash) {
        Entry e = byNameHash.get(hex(nameHash));
        if (e != null) e.registered = true;
    }

    /** Records or updates full ownership state for a name -- called
     *  whenever the wallet discovers (via its own synced transaction
     *  history) a REGISTER, RENEW, or FINALIZE output paying one of
     *  its own watched addresses. Creates a new entry if this name
     *  wasn't already known (the common case right after importing a
     *  seed that already owned names before this session started), or
     *  updates the existing one's renewal/ownership fields otherwise. */
    public void recordOwnership(String name, byte[] nameHash, int openHeight, int renewalHeight,
                                byte[] ownerTxid, int ownerOutputIndex) {
        Entry e = byNameHash.get(hex(nameHash));
        if (e == null) {
            e = new Entry();
            e.name = name;
            e.nameHash = nameHash;
            e.openHeight = openHeight;
            byNameHash.put(hex(nameHash), e);
        }
        e.registered = true;
        e.renewalHeight = renewalHeight;
        e.ownerTxid = ownerTxid;
        e.ownerOutputIndex = ownerOutputIndex;
    }

    public java.util.List<Entry> allOwnedNames() {
        java.util.List<Entry> result = new java.util.ArrayList<>();
        for (Entry e : byNameHash.values()) if (e.registered) result.add(e);
        return result;
    }

    /** Directly inserts an already-populated Entry -- used when
     *  restoring from persistent storage. */
    public void restoreEntry(Entry entry) {
        byNameHash.put(hex(entry.nameHash), entry);
    }

    public Entry get(byte[] nameHash) {
        return byNameHash.get(hex(nameHash));
    }

    public boolean isKnown(byte[] nameHash) {
        return byNameHash.containsKey(hex(nameHash));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}