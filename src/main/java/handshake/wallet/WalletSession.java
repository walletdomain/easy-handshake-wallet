package handshake.wallet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The top-level orchestrator: loads a wallet's full in-memory state
 * from WalletStorage, drives one complete sync pass (headers, balance,
 * active auctions, renewal deadlines), and saves everything back --
 * the piece that finally ties together every other class built across
 * this whole project into one coherent startup -> sync -> save flow.
 *
 * Deliberately built LAST, once every piece it needs to orchestrate
 * already existed and was independently verified -- see this whole
 * project's own discussion on why: building this earlier would have
 * meant either a shell substantially rewritten as each new piece
 * arrived, or wiring things in an order that didn't reflect the real,
 * final architecture.
 *
 * Uses two separate connections, deliberately: a filtered one (for
 * ordinary balance sync, via WalletBlockScanner) and an unfiltered one
 * (for auction/covenant scanning, via WalletAuctionEngine) -- the two
 * mechanisms this whole project settled on after extensive discussion
 * of the alternatives, kept on separate connections specifically so
 * toggling FILTERLOAD/FILTERCLEAR on one never risks interfering with
 * the other's own in-flight state.
 */
public class WalletSession {

    public final String walletName;
    public final String currency;
    public final byte[] walletSalt;
    public final WalletAddressManager addressManager;
    public final WalletUtxoSet utxoSet;
    public final WalletBidManager bidManager;
    public final WalletNameState nameState;
    public final WalletNameMonitor nameMonitor;
    public final WalletCovenantCache covenantCache;
    public final WalletAuctionMonitor auctionMonitor;
    public final WalletAuctionEngine auctionEngine;
    public final WalletHeaderChain headerChain;
    // Keyed by txid: a block that is scanned again (see the scan loop) replaces
    // its earlier, possibly less complete, record instead of duplicating it.
    private final java.util.Map<String, WalletStorage.ActivityRecord> pendingActivity = new java.util.LinkedHashMap<>();

    // Hex of every element (address hash or outpoint) already installed in
    // the bloom filter on the current filtered connection.
    private final java.util.Set<String> filterKnown = new java.util.HashSet<>();

    // Which account this wallet uses -- not sensitive (just an index,
    // not key material), kept so unlock() can re-derive accountKey at
    // exactly the same path a fresh open() would, without needing to
    // touch storage for anything beyond the passphrase check itself.
    private final int accountIndex;

    // Block-scan progress: every block up to and including scannedHeight
    // has been checked for this wallet's transactions, with no gaps.
    // scanStartPending means the starting point has not been worked out
    // yet (first sync of a new/restored wallet) -- see resolveScanStart().
    private volatile int scannedHeight = 0;
    private volatile boolean scanStartPending = false;
    private long birthTime = 0;
    private volatile Runnable checkpoint; // persists progress mid-sync

    private static final int SCAN_BATCH = 100;             // blocks requested per pipelined round trip
    private static final int HEADER_CHECKPOINT_EVERY = 20_000;
    private static final int SCAN_CHECKPOINT_EVERY = 1_000; // blocks
    private static final long BIRTH_SKEW_SECONDS = 86_400;  // start a day before the stated date

    /** Called periodically during a long sync to save progress (headers,
     *  scan height, UTXOs), so closing the wallet mid-restore does not
     *  lose hours of work. */
    public void setCheckpoint(Runnable checkpoint) { this.checkpoint = checkpoint; }

    public int scannedHeight() { return scannedHeight; }

    // The actually-sensitive, signing-capable key material -- NOT
    // public, NOT final. Cleared (zeroed, not just dropped) by lock(),
    // re-derived fresh by unlock(). Every other piece of this session
    // (addressManager, utxoSet, headerChain, the monitors) is
    // deliberately untouched by either: addressManager holds its own,
    // independent HDPublicKey (see its own field comment), so ordinary
    // background sync -- extending the watched-address gap limit
    // included -- keeps working exactly the same whether these three
    // fields are populated or null. Only actually signing a NEW
    // transaction needs them, via accountKeyForSigning() below, which
    // throws plainly if this session is currently locked rather than
    // silently working with stale or absent data.
    private byte[] seed;
    private HDKey master;
    private HDKey accountKey;
    private volatile boolean locked = false;

    private WalletSession(String walletName, String currency, byte[] seed, byte[] walletSalt, int accountIndex,
                          HDKey master, HDKey accountKey, WalletAddressManager addressManager, WalletUtxoSet utxoSet,
                          WalletBidManager bidManager, WalletNameState nameState, WalletHeaderChain headerChain) {
        this.walletName = walletName;
        this.currency = currency;
        this.seed = seed;
        this.walletSalt = walletSalt;
        this.accountIndex = accountIndex;
        this.master = master;
        this.accountKey = accountKey;
        this.addressManager = addressManager;
        this.utxoSet = utxoSet;
        this.bidManager = bidManager;
        this.nameState = nameState;
        this.nameMonitor = new WalletNameMonitor(nameState);
        this.covenantCache = new WalletCovenantCache();
        this.auctionMonitor = new WalletAuctionMonitor(bidManager);
        this.auctionEngine = new WalletAuctionEngine(covenantCache, auctionMonitor, bidManager);
        this.headerChain = headerChain;
    }

    /** Loads and fully reconstructs a wallet's in-memory session from
     *  persistent storage -- the real startup path. Every piece of
     *  state restored here (address gap-limit position, UTXOs, pending
     *  bids with their encrypted value/nonce, owned names, and the
     *  header chain itself) is exactly what a fresh WalletSession would
     *  otherwise have to rediscover from scratch by re-syncing the
     *  entire chain, which is the whole point of having built
     *  persistence at all. */
    public static WalletSession open(WalletStorage storage, String walletName, String passphrase)
            throws java.sql.SQLException, WalletCrypto.WrongPassphraseException {
        WalletStorage.OpenedWallet opened = storage.openWallet(walletName, passphrase);
        HDKey master = HDKey.masterFromSeed(opened.seed);
        HDKey accountKey = HandshakeKeyPath.deriveAccount(master, opened.accountIndex);

        // WalletAddressManager gets the NEUTERED (public-only) account
        // key, never the signing-capable one -- see this class's own
        // accountKey field comment, and WalletAddressManager's own, for
        // why that's what makes lock()/unlock() safe to do at all
        // without breaking background address discovery.
        WalletAddressManager addrMgr = new WalletAddressManager(accountKey.neuter());
        int extHighest = storage.loadHighestUsedIndex(walletName, HandshakeKeyPath.CHANGE_EXTERNAL);
        if (extHighest >= 0) addrMgr.markUsed(HandshakeKeyPath.CHANGE_EXTERNAL, extHighest);
        int intHighest = storage.loadHighestUsedIndex(walletName, HandshakeKeyPath.CHANGE_INTERNAL);
        if (intHighest >= 0) addrMgr.markUsed(HandshakeKeyPath.CHANGE_INTERNAL, intHighest);

        WalletUtxoSet utxoSet = new WalletUtxoSet();
        for (byte[] h : addrMgr.allWatchedAddressHashes()) utxoSet.watchAddress(h);
        for (WalletUtxoSet.Utxo u : storage.loadUtxos(walletName)) utxoSet.restoreUtxo(u);

        WalletBidManager bidMgr = new WalletBidManager();
        for (WalletBidManager.PendingBid b : storage.loadPendingBids(walletName, passphrase, opened.salt)) {
            bidMgr.restorePendingBid(b);
        }

        WalletNameState nameState = new WalletNameState();
        for (WalletNameState.Entry e : storage.loadNameState(walletName)) nameState.restoreEntry(e);

        // Headers are scoped by currency, not by wallet -- two wallets
        // of the same currency share one chain (see WalletStorage's own
        // comment on this table). opened.currency, not a caller-supplied
        // value, is deliberately the source of truth here: a wallet's
        // own currency is fixed at creation and never passed in
        // separately, so there's no way for this to reference the wrong
        // chain by a caller's mistake.
        WalletHeaderChain headerChain = new WalletHeaderChain();
        int highestStoredHeader = storage.loadHighestHeaderHeight(opened.currency);
        for (int h = 1; h <= highestStoredHeader; h++) {
            byte[] header = storage.loadHeader(opened.currency, h);
            if (header == null || !headerChain.tryAppend(header)) break; // stop at the first gap or invalid entry rather than silently skip ahead
        }

        WalletSession session = new WalletSession(walletName, opened.currency, opened.seed, opened.salt, opened.accountIndex,
                master, accountKey, addrMgr, utxoSet, bidMgr, nameState, headerChain);

        long[] scanState = storage.loadScanState(walletName);
        if (scanState == null) {
            // Wallet from before scan progress was tracked: it had been
            // scanning every header it stored, so carry on from there.
            session.scannedHeight = headerChain.tipHeight();
        } else if (scanState[0] < 0) {
            session.scanStartPending = true;   // first sync decides where to begin
            session.birthTime = scanState[1];
            session.scannedHeight = 0;
        } else {
            session.scannedHeight = (int) scanState[0];
            session.birthTime = scanState[1];
        }
        return session;
    }

    /** Works out where block scanning begins, once headers are known:
     *  birthTime -1 = brand-new wallet (start at the tip), 0 = from the
     *  first block, otherwise the first block dated a day before the
     *  stated time. */
    private void resolveScanStart(int tip) {
        if (!scanStartPending) return;
        int start;
        if (birthTime < 0) {
            start = tip;
        } else if (birthTime == 0) {
            start = 0;
        } else {
            long target = birthTime - BIRTH_SKEW_SECONDS;
            int lo = 1, hi = tip + 1; // first height whose header time >= target
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (HeaderUtil.time(headerChain.headerAt(mid)) >= target) hi = mid; else lo = mid + 1;
            }
            start = Math.max(0, lo - 1);
        }
        scannedHeight = start;
        scanStartPending = false;
        System.out.println("[Wallet] Scanning starts after block " + start + " (tip " + tip + ")");
    }

    /**
     * Actively destroys this session's signing-capable key material --
     * seed, master, and accountKey -- zeroing their bytes in place
     * (HDKey.destroy(); Arrays.fill() for the raw seed), not just
     * dropping references. Everything else on this session
     * (addressManager, utxoSet, headerChain, the monitors) is
     * completely untouched and keeps working normally: background
     * sync -- including extending the watched-address gap limit when
     * new activity is found -- needs none of what this just destroyed,
     * only addressManager's own, separately-held public key (see its
     * field comment). Only actually signing a new transaction is
     * affected, via accountKeyForSigning() throwing until unlock() is
     * called again.
     */
    public void lock() {
        if (locked) return;
        if (seed != null) Arrays.fill(seed, (byte) 0);
        seed = null;
        if (master != null) master.destroy();
        master = null;
        if (accountKey != null) accountKey.destroy();
        accountKey = null;
        locked = true;
    }

    /**
     * Re-derives this session's signing-capable key material from the
     * passphrase -- throws WalletCrypto.WrongPassphraseException
     * directly if it's wrong, leaving this session exactly as locked
     * as it was before the attempt (nothing partially restored on a
     * failed attempt). Everything else about this session (its
     * already-synced utxoSet, headerChain, etc. -- all of which may
     * well have kept updating the whole time this was locked, since
     * none of it needed what was destroyed) is untouched either way --
     * this only ever restores signing capability, it never rebuilds or
     * re-syncs anything, unlike a fresh open().
     */
    public void unlock(WalletStorage storage, String passphrase)
            throws java.sql.SQLException, WalletCrypto.WrongPassphraseException {
        WalletStorage.OpenedWallet opened = storage.openWallet(walletName, passphrase);
        this.seed = opened.seed;
        this.master = HDKey.masterFromSeed(seed);
        this.accountKey = HandshakeKeyPath.deriveAccount(master, accountIndex);
        this.locked = false;
    }

    public boolean isLocked() { return locked; }

    /** The account key, for signing a new transaction -- throws
     *  plainly if this session is currently locked, rather than
     *  returning null or silently stale data. Every real signing call
     *  site (WalletCovenantTxBuilder, WalletTxBuilder, etc.) should go
     *  through this, not a raw field read, precisely so "locked" is
     *  something the type system and runtime actually enforce here,
     *  not just something the GUI happens to currently respect. */
    public HDKey accountKeyForSigning() {
        if (locked || accountKey == null) {
            throw new IllegalStateException(
                    "This wallet is locked -- re-enter the passphrase (WalletSession.unlock()) before signing.");
        }
        return accountKey;
    }

    public static class SyncResult {
        public int headersReceived;
        public int blocksScannedForBalance;
        /** True when blocks remain to be scanned (the caller should run another pass soon). */
        public boolean moreToScan;
        public List<WalletAuctionMonitor.Alert> alerts = new ArrayList<>();
    }

    /** One full sync pass: extends the header chain, scans any newly-
     *  synced blocks for this wallet's own transactions (via the
     *  filtered connection), runs auction monitoring (via the
     *  unfiltered connection), and checks renewal deadlines. Returns
     *  every alert generated, from either monitor, in one combined
     *  list -- what actually gets shown to a person is a GUI concern,
     *  not this method's. */
    public SyncResult syncOnce(WalletPeerConnection filteredConn, WalletPeerConnection unfilteredConn,
                               long feeRatePerByte) throws Exception {
        return syncOnce(filteredConn, unfilteredConn, feeRatePerByte, null);
    }

    /** As above, reporting progress text to "progress" (may be null). */
    public SyncResult syncOnce(WalletPeerConnection filteredConn, WalletPeerConnection unfilteredConn,
                               long feeRatePerByte, java.util.function.Consumer<String> progress) throws Exception {
        SyncResult result = new SyncResult();
        int previousTip = headerChain.tipHeight();
        if (progress != null) progress.accept("Downloading block headers (have " + previousTip + ")...");

        // ── Header sync: keep asking until the peer has nothing more ────
        int lastCheckpointTip = previousTip;
        for (int round = 0; round < 1000; round++) {
            filteredConn.sendGetHeaders(headerChain.buildLocator());
            byte[] headersMsg = null;
            for (int skipped = 0; skipped < 20; skipped++) {
                headersMsg = filteredConn.readMessage(30_000);
                if (headersMsg == null) break;
                int mt = WalletPeerConnection.getMessageType(headersMsg);
                if (mt == WalletPeerConnection.MSG_HEADERS) break;
                headersMsg = null; // stray INV/PING/etc.: keep waiting for the HEADERS reply
            }
            if (headersMsg == null) break;
            List<byte[]> newHeaders = WalletPeerConnection.parseHeadersMessage(headersMsg);
            if (newHeaders.isEmpty()) break;
            int added = headerChain.appendAll(newHeaders);
            result.headersReceived += added;
            if (progress != null) {
                int peerH = filteredConn.peerHeight;
                progress.accept("Downloading block headers " + headerChain.tipHeight()
                        + (peerH > 0 ? " / " + peerH : "") + "...");
            }
            if (headerChain.tipHeight() - lastCheckpointTip >= HEADER_CHECKPOINT_EVERY) {
                lastCheckpointTip = headerChain.tipHeight();
                runCheckpoint();
            }
            if (added < newHeaders.size() || newHeaders.size() < 2000) break;
        }

        // ── Balance sync: scan blocks (pipelined) for this wallet's own transactions ──
        int scanTip = headerChain.tipHeight();
        resolveScanStart(scanTip);
        int sinceCheckpoint = 0;
        int redo = 0;
        int h = scannedHeight + 1;
        while (h <= scanTip) {
            int end = Math.min(h + SCAN_BATCH - 1, scanTip);
            List<byte[]> hashes = new ArrayList<>(end - h + 1);
            List<byte[]> headers = new ArrayList<>(end - h + 1);
            for (int i = h; i <= end; i++) {
                byte[] header = headerChain.headerAt(i);
                headers.add(header);
                hashes.add(HeaderUtil.hash(header));
            }
            if (progress != null) progress.accept("Scanning blocks " + h + " / " + scanTip);

            List<WalletBlockScanner.ScanResult> results =
                    WalletBlockScanner.scanBlocks(filteredConn, hashes, headers, 30_000);
            int done = 0;
            for (WalletBlockScanner.ScanResult sr : results) {
                if (!sr.success) {
                    System.out.println("[Wallet] Scan paused at block " + (h + done) + ": " + sr.error);
                    break;
                }
                int height = h + done;
                for (TxParser.ParsedTx tx : sr.matchedTransactions) {
                    addressManager.processTransaction(utxoSet, tx, height);
                    refreshWatchedAddresses(); // the gap window may have just moved
                    if (!touchesWallet(tx)) continue; // a bloom-filter false positive: nothing of ours in it
                    recordAnyNameOwnership(tx, height);
                    WalletStorage.ActivityRecord ar = classifyActivity(tx, height);
                    pendingActivity.put(ar.txid, ar);
                }
                scannedHeight = height; // only ever advanced over a contiguous run of verified blocks
                result.blocksScannedForBalance++;
                done++;
            }
            // New addresses or new coins found in this batch must be in the
            // peer's filter before their blocks are scanned: a payment to a
            // newly discovered address, or a spend of a coin found moments
            // ago, would otherwise not match. Add them and scan this batch
            // again (already-recorded transactions are simply re-applied).
            if (done == hashes.size() && redo < 8 && pushFilterUpdates(filteredConn) > 0) {
                redo++;
                scannedHeight = h - 1;
                continue;
            }
            redo = 0;
            sinceCheckpoint += done;
            if (sinceCheckpoint >= SCAN_CHECKPOINT_EVERY) {
                sinceCheckpoint = 0;
                runCheckpoint();
            }
            if (done < hashes.size()) break; // a block failed: stop here and retry from the same height next pass
            h = end + 1;
        }
        result.moreToScan = scannedHeight < headerChain.tipHeight();

        // ── Auction monitoring ───────────────────────────────────────────
        result.alerts.addAll(auctionEngine.syncAndCheck(unfilteredConn, headerChain,
                auctionMonitor.watchedAuctionsSnapshot(), utxoSet, addressManager, accountKey, feeRatePerByte));

        // ── Renewal monitoring ───────────────────────────────────────────
        result.alerts.addAll(nameMonitor.checkRenewalDeadlines(headerChain.tipHeight()));

        return result;
    }

    /** One name the wallet currently owns. */
    public static class OwnedName {
        public String name;          // "(unknown)" if the plaintext has not been seen
        public byte[] nameHash;
        public int covenantType;     // REGISTER/UPDATE/RENEW/TRANSFER/FINALIZE coin currently holding it
        public long coinValue;
        public int height;           // height of the transaction that created that coin
        public boolean transferring; // a TRANSFER is pending (not yet finalized)
    }

    /** Names the wallet owns RIGHT NOW: one per unspent name coin. A name that
     *  was transferred away has no unspent coin here any more, so it drops out
     *  by itself. */
    public List<OwnedName> ownedNames() {
        java.util.Map<String, OwnedName> byHash = new java.util.LinkedHashMap<>();
        for (WalletUtxoSet.Utxo u : utxoSet.getUnspentUtxos()) {
            if (!WalletUtxoSet.isNameCoin(u) || u.nameHash == null) continue;
            WalletNameState.Entry e = nameState.get(u.nameHash);
            OwnedName o = new OwnedName();
            o.name = e != null && e.name != null ? e.name : "(unknown)";
            o.nameHash = u.nameHash;
            o.covenantType = u.covenantType;
            o.coinValue = u.value;
            o.height = u.height;
            o.transferring = u.covenantType == 9;
            byHash.put(hex(u.nameHash), o);
        }
        List<OwnedName> list = new ArrayList<>(byHash.values());
        list.sort(java.util.Comparator.comparing((OwnedName o) -> o.name));
        return list;
    }

    /** True if the transaction pays one of our addresses or spends one of
     *  our known coins. */
    private boolean touchesWallet(TxParser.ParsedTx tx) {
        for (TxParser.Output out : tx.outputs) {
            if (out.addrHash != null && addressManager.findChainAndIndex(out.addrHash) != null) return true;
        }
        for (TxParser.Input in : tx.inputs) {
            if (in.isCoinbase()) continue;
            for (WalletUtxoSet.Utxo u : utxoSet.getAllUtxos()) {
                if (java.util.Arrays.equals(u.txid, in.prevHash) && u.outputIndex == in.prevIndex) return true;
            }
        }
        return false;
    }

    /** Makes the UTXO set watch every address the gap window now covers
     *  (the address manager extends its window as activity is found, but
     *  the UTXO set keeps its own copy of the watched addresses). */
    private void refreshWatchedAddresses() {
        for (byte[] h : addressManager.allWatchedAddressHashes()) utxoSet.watchAddress(h);
    }

    private static byte[] outpointBytes(WalletUtxoSet.Utxo u) {
        byte[] b = new byte[36];
        System.arraycopy(u.txid, 0, b, 0, 32);
        b[32] = (byte) u.outputIndex;
        b[33] = (byte) (u.outputIndex >>> 8);
        b[34] = (byte) (u.outputIndex >>> 16);
        b[35] = (byte) (u.outputIndex >>> 24);
        return b;
    }

    /** Everything the peer's bloom filter must contain: every watched
     *  address hash, plus the outpoint of every unspent coin (so a
     *  transaction that SPENDS one of our coins matches even when it pays
     *  nothing back to us). Also records what was included. */
    public List<byte[]> filterElements() {
        filterKnown.clear();
        List<byte[]> elements = new ArrayList<>();
        for (byte[] a : addressManager.allWatchedAddressHashes()) {
            elements.add(a);
            filterKnown.add(hex(a));
        }
        for (WalletUtxoSet.Utxo u : utxoSet.getUnspentUtxos()) {
            byte[] op = outpointBytes(u);
            elements.add(op);
            filterKnown.add(hex(op));
        }
        return elements;
    }

    /** Sends FILTERADD for every address or unspent outpoint not yet in the
     *  peer's filter. Returns how many were added. */
    private int pushFilterUpdates(WalletPeerConnection conn) throws Exception {
        int added = 0;
        for (byte[] a : addressManager.allWatchedAddressHashes()) {
            if (filterKnown.add(hex(a))) { conn.sendFilterAdd(a); added++; }
        }
        for (WalletUtxoSet.Utxo u : utxoSet.getUnspentUtxos()) {
            byte[] op = outpointBytes(u);
            if (filterKnown.add(hex(op))) { conn.sendFilterAdd(op); added++; }
        }
        return added;
    }

    private void runCheckpoint() {
        Runnable cp = checkpoint;
        if (cp == null) return;
        try { cp.run(); } catch (RuntimeException e) {
            System.out.println("[Wallet] Checkpoint save failed: " + e.getMessage());
        }
    }

    /** Watches every matched transaction's own outputs for a
     *  REGISTER/RENEW/FINALIZE covenant paying one of this wallet's own
     *  addresses, updating WalletNameState accordingly -- this is how
     *  the wallet discovers (or keeps up to date) which names it
     *  actually owns, purely from its own already-synced transaction
     *  history, no separate query needed. */
    void recordAnyNameOwnership(TxParser.ParsedTx tx, int height) {
        for (int i = 0; i < tx.outputs.size(); i++) {
            TxParser.Output out = tx.outputs.get(i);
            int type = out.covenant.type;
            if (type != TxParser.COV_REGISTER && type != TxParser.COV_RENEW && type != TxParser.COV_FINALIZE) continue;
            if (addressManager.findChainAndIndex(out.addrHash) == null) continue;
            if (out.covenant.items.isEmpty()) continue;

            byte[] nameHash = out.covenant.items.get(0).data;
            byte[] txid = Blake2b.hash256(java.util.Arrays.copyOf(tx.raw, tx.baseSize));
            WalletNameState.Entry existing = nameState.get(nameHash);
            // FINALIZE's own covenant items DO carry the plaintext name
            // (item index 2, confirmed from the real, verified spec) --
            // a genuinely better source than falling back to
            // "(unknown)" when this is the first time this name has
            // ever been seen by this wallet (e.g. right after importing
            // a seed that already owned it). REGISTER/RENEW don't carry
            // it at all, so for those, an already-tracked name (from its
            // own earlier OPEN/BID) keeps its real name; a genuinely
            // new discovery is labeled until a FINALIZE or a later
            // lookup fills it in.
            String name;
            if (existing != null) {
                name = existing.name;
            } else if (type == TxParser.COV_FINALIZE && out.covenant.items.size() > 2) {
                name = new String(out.covenant.items.get(2).data, java.nio.charset.StandardCharsets.US_ASCII);
            } else {
                name = "(unknown)";
            }
            int openHeight = existing != null ? existing.openHeight : height; // best available guess if this name's own OPEN was never seen by this wallet
            nameState.recordOwnership(name, nameHash, openHeight, height, txid, i);
        }
    }

    /**
     * Classifies one matched transaction's overall effect on this
     * wallet into a single ActivityRecord for the "Recent activity"
     * feed -- one row per transaction, matching how an actual person
     * thinks about their own history (a send with change coming back
     * is still just "one send", not two separate lines).
     * <p>
     * Deliberately net-amount, not gross: amount is simply this
     * wallet's total received minus total spent in this one
     * transaction, which uniformly covers a plain receive (positive),
     * a plain send (negative), AND a covenant operation like a renewal
     * (typically a small negative amount -- exactly the fee -- since
     * ownership just moves from one of this wallet's own outputs to
     * another, not a special case requiring its own separate math).
     * <p>
     * Scope: only REGISTER/UPDATE/RENEW/TRANSFER/FINALIZE/REVOKE
     * outputs paying this wallet get a specific covenant-named kind
     * (matching recordAnyNameOwnership's own pattern for resolving a
     * name); OPEN/BID/REVEAL/REDEEM fall through to the plain
     * received/sent classification below rather than a dedicated
     * label. Those four are already what WalletAuctionMonitor exists
     * to track through an auction's own lifecycle -- duplicating that
     * here would just be a second, easily-diverging source of truth
     * for the same information.
     */
    private WalletStorage.ActivityRecord classifyActivity(TxParser.ParsedTx tx, int height) {
        byte[] txid = Blake2b.hash256(java.util.Arrays.copyOf(tx.raw, tx.baseSize));

        // Name coins (REGISTER..FINALIZE) carry the name's auction value, not
        // spendable money, and that value just moves to the name's next coin
        // (possibly another owner's), so it is left out of the net amount.
        long received = 0;
        for (TxParser.Output out : tx.outputs) {
            if (addressManager.findChainAndIndex(out.addrHash) == null) continue;
            if (out.covenant != null && out.covenant.type >= 6 && out.covenant.type <= 10) continue;
            received += out.value;
        }

        long sent = 0;
        long inputTotal = 0;       // value of every input (name coins included)
        boolean allInputsOurs = true;
        int ourInputs = 0;
        for (TxParser.Input in : tx.inputs) {
            if (in.isCoinbase()) { allInputsOurs = false; continue; }
            boolean found = false;
            for (WalletUtxoSet.Utxo u : utxoSet.getAllUtxos()) {
                if (java.util.Arrays.equals(u.txid, in.prevHash) && u.outputIndex == in.prevIndex) {
                    if (!WalletUtxoSet.isNameCoin(u)) sent += u.value;
                    inputTotal += u.value;
                    ourInputs++;
                    found = true;
                    break;
                }
            }
            if (!found) allInputsOurs = false;
        }
        // The fee is known exactly only when every input is one of ours;
        // otherwise someone else paid it (Bob shows 0 for those).
        long fee = 0;
        if (allInputsOurs && ourInputs > 0) {
            long outTotal = 0;
            for (TxParser.Output out : tx.outputs) outTotal += out.value;
            fee = Math.max(0, inputTotal - outTotal);
        }
        java.util.LinkedHashSet<String> domainSet = new java.util.LinkedHashSet<>();
        String txType = null;

        String kind = null;
        String label = null;
        for (TxParser.Output out : tx.outputs) {
            // Any output counts, not just ours: a name sent OUT (e.g. a FINALIZE to
            // another owner) has its covenant on someone else's address.
            if (out.covenant == null) continue;
            int type = out.covenant.type;
            String covenantKind = switch (type) {
                case TxParser.COV_OPEN     -> "Opened";
                case TxParser.COV_BID      -> "Bid";
                case TxParser.COV_REVEAL   -> "Revealed";
                case TxParser.COV_REDEEM   -> "Redeemed";
                case TxParser.COV_REGISTER -> "Registered";
                case TxParser.COV_UPDATE   -> "Updated";
                case TxParser.COV_RENEW    -> "Renewed";
                case TxParser.COV_TRANSFER -> "Transferred";
                case TxParser.COV_FINALIZE -> "Finalized";
                case TxParser.COV_REVOKE   -> "Revoked";
                default -> null;
            };
            if (covenantKind == null) continue;
            String nm = nameFor(out.covenant);
            if (kind == null) {
                kind = covenantKind;       // one label per transaction -- first recognizable covenant wins
                label = nm;
                txType = covenantTypeName(type0(out));
            }
            if (!"(unknown)".equals(nm)) domainSet.add(nm);
            else if (domainSet.isEmpty()) domainSet.add(nm);
        }

        if (kind == null) {
            if (received >= sent) {
                kind = "Received";
                label = firstWatchedOutputAddress(tx);
            } else {
                kind = "Sent";
                label = firstUnwatchedOutputAddress(tx);
            }
        }

        WalletStorage.ActivityRecord r = new WalletStorage.ActivityRecord();
        r.txid = hex(txid);
        r.height = height;
        r.kind = kind;
        r.label = label != null ? label : "(unknown)";
        r.amount = received - sent;
        r.recordedAt = System.currentTimeMillis() / 1000;
        r.fee = fee;
        r.domains = String.join(", ", domainSet);
        if (txType != null) {
            r.type = txType;
        } else if ("Sent".equals(kind)) {
            r.type = "SEND";
        } else {
            r.type = "RECEIVE";
        }
        long t = 0;
        try { t = HeaderUtil.time(headerChain.headerAt(height)); } catch (RuntimeException ignored) { }
        r.blockTime = t;
        return r;
    }

    private static int type0(TxParser.Output out) { return out.covenant.type; }

    private static String covenantTypeName(int t) {
        return switch (t) {
            case TxParser.COV_OPEN -> "OPEN";
            case TxParser.COV_BID -> "BID";
            case TxParser.COV_REVEAL -> "REVEAL";
            case TxParser.COV_REDEEM -> "REDEEM";
            case TxParser.COV_REGISTER -> "REGISTER";
            case TxParser.COV_UPDATE -> "UPDATE";
            case TxParser.COV_RENEW -> "RENEW";
            case TxParser.COV_TRANSFER -> "TRANSFER";
            case TxParser.COV_FINALIZE -> "FINALIZE";
            case TxParser.COV_REVOKE -> "REVOKE";
            default -> "OTHER";
        };
    }

    // nameHash (hex) -> plaintext name, learned from covenants as they are seen.
    private final java.util.Map<String, String> knownNames = new java.util.HashMap<>();

    /** The plaintext name a covenant refers to, or "(unknown)". OPEN, BID and
     *  FINALIZE covenants carry the name itself; the others only its hash, so
     *  those are looked up from names already seen or tracked. */
    private String nameFor(TxParser.Covenant cov) {
        if (cov == null || cov.items.isEmpty()) return "(unknown)";
        byte[] nameHash = cov.items.get(0).data;
        String key = hex(nameHash);
        if ((cov.type == TxParser.COV_OPEN || cov.type == TxParser.COV_BID || cov.type == TxParser.COV_FINALIZE)
                && cov.items.size() > 2) {
            String n = new String(cov.items.get(2).data, java.nio.charset.StandardCharsets.US_ASCII);
            knownNames.put(key, n);
            return n;
        }
        String n = knownNames.get(key);
        if (n != null) return n;
        WalletNameState.Entry e = nameState.get(nameHash);
        return e != null && e.name != null ? e.name : "(unknown)";
    }

    /**
     * Shows a transaction this wallet has just made (for example an OPEN or a
     * BID) at the top of Recent activity straight away, before it is mined.
     * It is stored with PENDING_HEIGHT and replaced by the real record when
     * the block containing it is scanned. Whatever builds and broadcasts the
     * transaction should call this, then saveState() and refresh the screens.
     */
    public void recordPendingActivity(String txidHex, String kind, String label, long amount) {
        WalletStorage.ActivityRecord r = new WalletStorage.ActivityRecord();
        r.txid = txidHex;
        r.height = WalletStorage.PENDING_HEIGHT;
        r.kind = kind;
        r.label = label;
        r.amount = amount;
        r.recordedAt = System.currentTimeMillis() / 1000;
        r.type = kind == null ? "" : kind.toUpperCase();
        r.domains = label == null ? "" : label;
        pendingActivity.put(r.txid, r);
    }

    private String firstWatchedOutputAddress(TxParser.ParsedTx tx) {
        for (TxParser.Output out : tx.outputs) {
            if (addressManager.findChainAndIndex(out.addrHash) != null) {
                return Bech32.encode("hs", out.addrVersion, out.addrHash);
            }
        }
        return null;
    }

    /** The actual recipient of a send, as best this can tell: the
     *  first output that does NOT belong to this wallet (typically the
     *  payee; the rest, if any, are this wallet's own change). Falls
     *  back to this wallet's own first output for the rare case where
     *  literally every output belongs to this wallet (a self-send,
     *  e.g. UTXO consolidation) -- there's no external recipient to
     *  name in that case, so this wallet's own address is the most
     *  accurate label available, not a real ambiguity being papered
     *  over. */
    private String firstUnwatchedOutputAddress(TxParser.ParsedTx tx) {
        for (TxParser.Output out : tx.outputs) {
            if (addressManager.findChainAndIndex(out.addrHash) == null) {
                return Bech32.encode("hs", out.addrVersion, out.addrHash);
            }
        }
        return firstWatchedOutputAddress(tx);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /**
     * Persists everything about this session EXCEPT pending bids --
     * address gap-limit position, UTXOs, owned names, recent activity,
     * and any newly-synced headers. Deliberately takes no passphrase at
     * all: none of what this saves is encrypted (see WalletStorage's
     * own class comment -- only the seed and a pending bid's real
     * value/nonce are field-level encrypted), so there's no reason to
     * gate ordinary, frequent persistence behind a credential nobody
     * should need to keep re-entering just to keep the wallet's own
     * on-disk state honestly reflecting what it already knows in
     * memory. This is what WalletSyncLoop actually calls after every
     * successful cycle -- real, continuous persistence, working
     * whether the session is locked or not (see WalletSession's own
     * lock()/unlock() comments for why that's safe: nothing here
     * touches accountKeyForSigning() or anything it gates), with zero
     * passphrase prompts needed for it.
     */
    public void saveState(WalletStorage storage) throws java.sql.SQLException {
        storage.saveAddressState(walletName, HandshakeKeyPath.CHANGE_EXTERNAL,
                addressManager.highestUsed(HandshakeKeyPath.CHANGE_EXTERNAL));
        storage.saveAddressState(walletName, HandshakeKeyPath.CHANGE_INTERNAL,
                addressManager.highestUsed(HandshakeKeyPath.CHANGE_INTERNAL));

        for (WalletUtxoSet.Utxo u : utxoSet.getAllUtxos()) storage.saveUtxo(walletName, u);

        for (WalletNameState.Entry e : nameState.allOwnedNames()) storage.saveNameState(walletName, e);

        for (WalletStorage.ActivityRecord r : pendingActivity.values()) {
            storage.recordActivity(walletName, currency, r);
        }
        pendingActivity.clear(); // already persisted (idempotently, if this is ever called twice) -- no reason to keep re-sending the same rows on every future call this session

        int highestStored = storage.loadHighestHeaderHeight(currency);
        for (int h = highestStored + 1; h <= headerChain.tipHeight(); h++) {
            storage.saveHeader(currency, h, headerChain.headerAt(h));
        }
        // Only once a real starting point has been chosen (an unresolved
        // wallet stays "pending" in storage until its first scan decides).
        if (!scanStartPending) storage.saveScannedHeight(walletName, scannedHeight);
    }

    /**
     * Persists pending bids specifically -- the one piece saveState()
     * above deliberately leaves out, since it's the only thing that
     * actually needs a passphrase (to re-encrypt each bid's real
     * value/nonce correctly; passing anything else, including null,
     * would silently encrypt under the wrong key rather than fail
     * loudly, which is exactly the kind of mistake worth making
     * structurally impossible rather than just documenting). Meant to
     * be called right after placing or updating a bid -- a moment that
     * already required the passphrase anyway, via
     * accountKeyForSigning(), to actually sign the bid transaction in
     * the first place -- not on some separate, additional prompt.
     */
    public void savePendingBids(WalletStorage storage, String passphrase) throws java.sql.SQLException {
        for (byte[] nameHash : trackedBidNameHashes()) {
            WalletBidManager.PendingBid bid = bidManager.getPendingBid(nameHash);
            if (bid != null) storage.savePendingBid(walletName, bid, passphrase, walletSalt);
        }
    }

    /** Convenience: both of the above in one call, for a caller that
     *  genuinely has the passphrase in hand and wants everything saved
     *  at once (e.g. a real, full shutdown flow, if one is ever built
     *  to retain the passphrase that long -- see WalletLockTimer's own
     *  comment on why that's a deliberate choice this project hasn't
     *  made). WalletSyncLoop does NOT call this -- it calls saveState()
     *  alone, specifically to avoid needing the passphrase at all for
     *  its own, much more frequent saves. */
    public void save(WalletStorage storage, String passphrase) throws java.sql.SQLException {
        saveState(storage);
        savePendingBids(storage, passphrase);
    }

    private List<byte[]> trackedBidNameHashes() {
        // WalletBidManager doesn't expose a direct "all pending" list
        // (see its own API) -- names actively being watched by the
        // auction monitor are exactly the set with a pending bid worth
        // persisting, so that's used as the source of truth here rather
        // than adding a redundant second list.
        List<byte[]> result = new ArrayList<>();
        for (WalletAuctionMonitor.AuctionState state : auctionMonitor.watchedAuctionsSnapshot()) {
            result.add(state.nameHash);
        }
        return result;
    }
}