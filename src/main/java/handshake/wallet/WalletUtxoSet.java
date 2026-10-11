package handshake.wallet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks the wallet's own unspent outputs and balance, built up by
 * feeding it matched, parsed transactions from WalletBlockScanner in
 * block-height order. Deliberately in-memory only for now -- real
 * persistence (the SQLite-backed wallet database discussed earlier)
 * is a separate piece of work; this class is the pure logic that a
 * persistence layer would sit on top of and periodically save.
 *
 * Correctness here depends on being fed transactions in the actual
 * order they were confirmed (ascending height, and within a block, the
 * block's own transaction order) -- exactly what naturally happens by
 * scanning blocks forward from the wallet's birth height, but worth
 * stating explicitly since applyTransaction() itself has no way to
 * detect or correct an out-of-order call: a spend fed in before the
 * output it spends would simply find nothing to mark spent.
 */
public class WalletUtxoSet {

    public static class Utxo {
        public final byte[] txid;
        public final int outputIndex;
        public final long value;
        public final byte[] addrHash;
        public final int height;
        public boolean spent;
        public Integer spentAtHeight;
        /** Covenant type of this output (0 = plain coin; see TxParser.COV_*). */
        public int covenantType;
        /** For a name-related output: the name hash (first covenant item), else null. */
        public byte[] nameHash;

        Utxo(byte[] txid, int outputIndex, long value, byte[] addrHash, int height) {
            this.txid = txid;
            this.outputIndex = outputIndex;
            this.value = value;
            this.addrHash = addrHash;
            this.height = height;
        }
    }

    // Keyed by "txidHex:outputIndex" -- a stable, unique identifier for
    // an outpoint regardless of confirmation order.
    private final Map<String, Utxo> utxos = new LinkedHashMap<>();
    private final java.util.Set<String> watchedAddresses = new java.util.HashSet<>();

    public void watchAddress(byte[] addrHash) {
        watchedAddresses.add(hex(addrHash));
    }

    /** Directly inserts an already-constructed Utxo -- used when
     *  restoring from persistent storage, where the UTXO's full state
     *  (including spent/spentAtHeight) is already known and shouldn't
     *  be re-derived from re-processing a transaction. */
    public void restoreUtxo(Utxo utxo) {
        String key = hex(utxo.txid) + ":" + utxo.outputIndex;
        utxos.put(key, utxo);
    }

    public boolean isWatched(byte[] addrHash) {
        return addrHash != null && watchedAddresses.contains(hex(addrHash));
    }

    /** Applies one confirmed transaction: any output paying a watched
     *  address becomes a new UTXO; any input spending an outpoint this
     *  set already knows about marks that UTXO spent. Both checks run
     *  independently within the same call, so a transaction that both
     *  spends an old UTXO and creates a new one (e.g. a send with
     *  change back to the wallet) is handled correctly in one pass. */
    public void applyTransaction(TxParser.ParsedTx tx, int height) {
        byte[] txid = Blake2b.hash256(Arrays.copyOf(tx.raw, tx.baseSize));

        for (TxParser.Input input : tx.inputs) {
            if (input.isCoinbase()) continue;
            String key = hex(input.prevHash) + ":" + input.prevIndex;
            Utxo existing = utxos.get(key);
            if (existing != null && !existing.spent) {
                existing.spent = true;
                existing.spentAtHeight = height;
            }
        }

        for (int i = 0; i < tx.outputs.size(); i++) {
            TxParser.Output output = tx.outputs.get(i);
            if (isWatched(output.addrHash)) {
                String key = hex(txid) + ":" + i;
                if (!utxos.containsKey(key)) {
                    Utxo u = new Utxo(txid, i, output.value, output.addrHash, height);
                    if (output.covenant != null) {
                        u.covenantType = output.covenant.type;
                        if (!output.covenant.items.isEmpty()) u.nameHash = output.covenant.items.get(0).data;
                    }
                    utxos.put(key, u);
                }
            }
        }
    }

    // Covenant types (hsd): 0 NONE, 1 CLAIM, 2 OPEN, 3 BID, 4 REVEAL, 5 REDEEM,
    // 6 REGISTER, 7 UPDATE, 8 RENEW, 9 TRANSFER, 10 FINALIZE, 11 REVOKE.

    /** A coin that can fund an ordinary payment: a plain output (or a REDEEM,
     *  which returns a lost bid as ordinary money). Name coins and coins locked
     *  in an auction are NOT spendable this way. */
    public static boolean isSpendable(Utxo u) {
        return u.covenantType == 0 || u.covenantType == 5;
    }

    /** Coin locked in an open auction (BID or REVEAL). */
    public static boolean isAuctionLocked(Utxo u) {
        return u.covenantType == 3 || u.covenantType == 4;
    }

    /** Coin that carries a name the wallet owns (REGISTER..FINALIZE). */
    public static boolean isNameCoin(Utxo u) {
        return u.covenantType >= 6 && u.covenantType <= 10;
    }

    /** Spendable balance: unspent plain coins only. The value carried by a name
     *  coin (e.g. what was paid for the name at auction) is part of owning the
     *  name, not money that can be spent, and is not counted here. */
    public long getBalance() {
        long total = 0;
        for (Utxo u : utxos.values()) {
            if (!u.spent && isSpendable(u)) total += u.value;
        }
        return total;
    }

    /** Value locked in open auctions (BID/REVEAL coins). */
    public long getLockedBalance() {
        long total = 0;
        for (Utxo u : utxos.values()) {
            if (!u.spent && isAuctionLocked(u)) total += u.value;
        }
        return total;
    }

    /** Value carried by name coins -- shown separately, not part of the balance. */
    public long getNameCoinValue() {
        long total = 0;
        for (Utxo u : utxos.values()) {
            if (!u.spent && isNameCoin(u)) total += u.value;
        }
        return total;
    }

    /** Every unspent coin of any kind (used for the bloom filter and lookups). */
    public List<Utxo> getUnspentUtxos() {
        List<Utxo> result = new ArrayList<>();
        for (Utxo u : utxos.values()) {
            if (!u.spent) result.add(u);
        }
        return result;
    }

    /** Unspent coins that may fund an ordinary payment or fee. */
    public List<Utxo> getSpendableUtxos() {
        List<Utxo> result = new ArrayList<>();
        for (Utxo u : utxos.values()) {
            if (!u.spent && isSpendable(u)) result.add(u);
        }
        return result;
    }

    public List<Utxo> getAllUtxos() {
        return new ArrayList<>(utxos.values());
    }

    public int size() {
        return utxos.size();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}