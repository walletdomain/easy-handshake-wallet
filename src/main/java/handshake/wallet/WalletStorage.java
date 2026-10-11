package handshake.wallet;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-file SQLite-backed persistence for the wallet -- one physical
 * file holding possibly-multiple wallets, matching this project's own
 * earlier design discussion: a wallets table keyed by name, with every
 * other table carrying that wallet's name as a foreign key, rather than
 * one file per wallet or a directory of loose files.
 *
 * Only the seed and a pending bid's real value/nonce are field-level
 * encrypted (see WalletCrypto's own comment for why that's the right
 * scope, not full-database encryption) -- everything else (addresses'
 * highest-used index, UTXOs, name state, headers) is plain, since none
 * of it is a secret the way those specific fields are; it's either
 * already public on-chain or derivable from the seed regardless.
 *
 * Deliberately does NOT persist individual derived addresses -- only
 * the highest-used index per chain per wallet. WalletAddressManager can
 * always re-derive every address (and re-apply its own gap-limit
 * extension) from that plus the seed; storing each address
 * individually would just be redundant, derivable data taking up space
 * for no benefit.
 */
public class WalletStorage implements AutoCloseable {

    private final Connection conn;

    private WalletStorage(Connection conn) {
        this.conn = conn;
    }

    public static WalletStorage open(String dbPath) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQLite JDBC driver not on classpath", e);
        }
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        WalletStorage storage = new WalletStorage(conn);
        storage.createSchema();
        return storage;
    }

    private void createSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS wallets (
                    name TEXT PRIMARY KEY,
                    currency TEXT NOT NULL,
                    encrypted_seed BLOB NOT NULL,
                    seed_salt BLOB NOT NULL,
                    created_at INTEGER NOT NULL,
                    account_index INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS address_state (
                    wallet_name TEXT NOT NULL,
                    chain INTEGER NOT NULL,
                    highest_used_index INTEGER NOT NULL,
                    PRIMARY KEY (wallet_name, chain)
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS utxos (
                    wallet_name TEXT NOT NULL,
                    txid TEXT NOT NULL,
                    output_index INTEGER NOT NULL,
                    value INTEGER NOT NULL,
                    addr_hash TEXT NOT NULL,
                    height INTEGER NOT NULL,
                    spent INTEGER NOT NULL DEFAULT 0,
                    spent_at_height INTEGER,
                    PRIMARY KEY (wallet_name, txid, output_index)
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS pending_bids (
                    wallet_name TEXT NOT NULL,
                    name_hash TEXT NOT NULL,
                    name TEXT NOT NULL,
                    encrypted_value BLOB NOT NULL,
                    lockup INTEGER NOT NULL,
                    encrypted_nonce BLOB NOT NULL,
                    blind TEXT NOT NULL,
                    open_height INTEGER NOT NULL,
                    bid_addr_hash TEXT NOT NULL,
                    bid_txid TEXT,
                    bid_output_index INTEGER,
                    PRIMARY KEY (wallet_name, name_hash)
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS name_state (
                    wallet_name TEXT NOT NULL,
                    name_hash TEXT NOT NULL,
                    name TEXT NOT NULL,
                    open_height INTEGER NOT NULL,
                    registered INTEGER NOT NULL DEFAULT 0,
                    renewal_height INTEGER NOT NULL DEFAULT 0,
                    owner_txid TEXT,
                    owner_output_index INTEGER,
                    PRIMARY KEY (wallet_name, name_hash)
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS headers (
                    currency TEXT NOT NULL,
                    height INTEGER NOT NULL,
                    raw_header BLOB NOT NULL,
                    PRIMARY KEY (currency, height)
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS seed_nodes (
                    currency TEXT NOT NULL,
                    pubkey_b32 TEXT NOT NULL,
                    ip TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    added_at INTEGER NOT NULL,
                    score INTEGER NOT NULL DEFAULT 50,
                    consecutive_failures INTEGER NOT NULL DEFAULT 0,
                    last_attempt_at INTEGER NOT NULL DEFAULT 0,
                    last_success_at INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (currency, pubkey_b32, ip)
                )""");
            // Deliberately no UPDATE path anywhere for this table (see
            // recordActivity()'s own comment): every row here describes
            // something a specific, already-confirmed block proved
            // happened, at a specific height, in a specific transaction.
            // That's fundamentally different from seed_nodes' score
            // above, which genuinely needs to change over time -- this
            // never should, short of a chain reorg invalidating the
            // block it came from entirely (see deleteActivityAboveHeight()).
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS transaction_history (
                    wallet_name TEXT NOT NULL,
                    currency TEXT NOT NULL,
                    txid TEXT NOT NULL,
                    height INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    label TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    recorded_at INTEGER NOT NULL,
                    PRIMARY KEY (wallet_name, currency, txid)
                )""");
            // Per-wallet scan progress. scanned_height = highest block
            // height whose transactions have been checked for this wallet
            // (-1 = not yet resolved: the first sync works it out from
            // birth_time). birth_time: 0 = scan from the start of the chain,
            // -1 = brand-new wallet (nothing can predate it: start at the
            // tip), >0 = unix seconds the wallet is known to date from.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS wallet_scan_state (
                    wallet_name TEXT PRIMARY KEY,
                    scanned_height INTEGER NOT NULL,
                    birth_time INTEGER NOT NULL
                )""");
        }
        migrateUtxoCovenantColumns();
        migrateHistoryColumns();
        migrateSeedNodesScoringColumns();
        mergeSeedsFromResource();
    }

    /**
     * Adds the score/consecutive_failures/last_attempt_at/last_success_at
     * columns to a seed_nodes table that may already exist from a run
     * before scoring existed -- CREATE TABLE IF NOT EXISTS above only
     * takes effect for a brand-new table, so an existing database
     * needs these added explicitly. SQLite has no "ADD COLUMN IF NOT
     * EXISTS", so this checks PRAGMA table_info() first and only adds
     * whichever of the four are actually missing, rather than adding
     * unconditionally (which would throw "duplicate column" on every
     * run after the first).
     */
    /** Adds the covenant columns to a utxos table created before coins
     *  recorded their covenant type (old rows read as plain coins). */
    /** Adds the History-screen columns to a transaction_history table created
     *  before they existed (old rows read as unknown until re-scanned). */
    private void migrateHistoryColumns() throws SQLException {
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(transaction_history)")) {
            while (rs.next()) existing.add(rs.getString("name"));
        }
        try (Statement stmt = conn.createStatement()) {
            if (!existing.contains("tx_type")) stmt.execute("ALTER TABLE transaction_history ADD COLUMN tx_type TEXT NOT NULL DEFAULT ''");
            if (!existing.contains("domains")) stmt.execute("ALTER TABLE transaction_history ADD COLUMN domains TEXT NOT NULL DEFAULT ''");
            if (!existing.contains("fee")) stmt.execute("ALTER TABLE transaction_history ADD COLUMN fee INTEGER NOT NULL DEFAULT 0");
            if (!existing.contains("block_time")) stmt.execute("ALTER TABLE transaction_history ADD COLUMN block_time INTEGER NOT NULL DEFAULT 0");
        }
    }

    private void migrateUtxoCovenantColumns() throws SQLException {
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(utxos)")) {
            while (rs.next()) existing.add(rs.getString("name"));
        }
        try (Statement stmt = conn.createStatement()) {
            if (!existing.contains("cov_type")) stmt.execute("ALTER TABLE utxos ADD COLUMN cov_type INTEGER NOT NULL DEFAULT 0");
            if (!existing.contains("name_hash")) stmt.execute("ALTER TABLE utxos ADD COLUMN name_hash TEXT");
        }
    }

    private void migrateSeedNodesScoringColumns() throws SQLException {
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(seed_nodes)")) {
            while (rs.next()) existing.add(rs.getString("name"));
        }
        try (Statement stmt = conn.createStatement()) {
            if (!existing.contains("score")) {
                stmt.execute("ALTER TABLE seed_nodes ADD COLUMN score INTEGER NOT NULL DEFAULT 50");
            }
            if (!existing.contains("consecutive_failures")) {
                stmt.execute("ALTER TABLE seed_nodes ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0");
            }
            if (!existing.contains("last_attempt_at")) {
                stmt.execute("ALTER TABLE seed_nodes ADD COLUMN last_attempt_at INTEGER NOT NULL DEFAULT 0");
            }
            if (!existing.contains("last_success_at")) {
                stmt.execute("ALTER TABLE seed_nodes ADD COLUMN last_success_at INTEGER NOT NULL DEFAULT 0");
            }
        }
    }

    // ── Seed nodes ───────────────────────────────────────────────────────
    // Global, not per-wallet -- the same network bootstrap list applies
    // regardless of which wallet in this file happens to be open.

    public static class SeedNode {
        public String pubkeyB32, ip, source;
        public int port;
        public int score;
        public int consecutiveFailures;
        public long lastAttemptAt, lastSuccessAt;
        public String uri() { return pubkeyB32 + "@" + ip + ":" + port; }
    }

    private static final int DEFAULT_BRONTIDE_PORT = 44806; // real hsd mainnet default, already established elsewhere in this project

    /** Classpath location of the bootstrap seed list -- the same file
     *  name and the same line format as the node project's own
     *  seeds.txt: one seed per line, "brontideKey|ip|port|label"; blank
     *  lines and lines starting with '#' are ignored. Lives in the
     *  wallet's src/main/resources folder. */
    static final String SEEDS_RESOURCE = "/seeds.txt";

    /**
     * Merges every valid entry of the bundled seeds.txt into seed_nodes
     * on every open(). Purely additive, like the node's own merge: a new
     * line is inserted, a line for a (key, ip) already present only
     * refreshes its port, and an existing row's score, failure count and
     * timestamps are never touched. Malformed lines are skipped (and
     * logged) rather than failing the whole wallet. A missing file is a
     * packaging error and is reported as one, since a wallet with no
     * seeds can never bootstrap at all.
     */
    private void mergeSeedsFromResource() throws SQLException {
        try (java.io.InputStream in = WalletStorage.class.getResourceAsStream(SEEDS_RESOURCE)) {
            if (in == null) {
                throw new SQLException("seeds.txt not found on the classpath -- put it in the wallet's "
                        + "src/main/resources folder so it is packaged into the jar.");
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            String line;
            int lineNo = 0, added = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.startsWith("\uFEFF")) line = line.substring(1).trim(); // BOM from a Windows editor
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\\|");
                if (f.length < 2) { System.err.println("[Wallet] seeds.txt line " + lineNo + ": expected key|ip|port|label, skipped"); continue; }
                String key = f[0].trim().toLowerCase();
                String ip = f[1].trim();
                int port = DEFAULT_BRONTIDE_PORT;
                try {
                    if (f.length >= 3 && !f[2].trim().isEmpty()) port = Integer.parseInt(f[2].trim());
                } catch (NumberFormatException e) { port = -1; }
                if (!key.matches("[a-z2-7]{50,56}") || Base32.decode(key).length != 33
                        || ip.isEmpty() || port < 1 || port > 65535) {
                    System.err.println("[Wallet] seeds.txt line " + lineNo + ": invalid entry, skipped");
                    continue;
                }
                addSeedNode(CURRENCY_HNS, key, ip, port, "default");
                added++;
            }
            System.out.println("[Wallet] Loaded " + added + " seed(s) from seeds.txt");
        } catch (java.io.IOException e) {
            throw new SQLException("Could not read seeds.txt: " + e.getMessage(), e);
        }
    }

    /** Seed nodes are scoped by currency -- every Handshake wallet in
     *  this file shares one Handshake seed list, the same way they'll
     *  come to share one Handshake header chain below; a future
     *  Bitcoin wallet gets its own, entirely separate list, using
     *  Bitcoin's own bootstrap addresses, not these. */
    public void addSeedNode(String currency, String pubkeyB32, String ip, int port, String source) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO seed_nodes (currency, pubkey_b32, ip, port, source, added_at) VALUES (?,?,?,?,?,?) "
                        + "ON CONFLICT(currency, pubkey_b32, ip) DO UPDATE SET port = excluded.port")) {
            ps.setString(1, currency);
            ps.setString(2, pubkeyB32);
            ps.setString(3, ip);
            ps.setInt(4, port);
            ps.setString(5, source);
            ps.setLong(6, System.currentTimeMillis() / 1000);
            ps.executeUpdate();
        }
    }

    public void removeSeedNode(String currency, String pubkeyB32, String ip) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM seed_nodes WHERE currency = ? AND pubkey_b32 = ? AND ip = ?")) {
            ps.setString(1, currency);
            ps.setString(2, pubkeyB32);
            ps.setString(3, ip);
            ps.executeUpdate();
        }
    }

    public List<SeedNode> listSeedNodes(String currency) throws SQLException {
        List<SeedNode> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM seed_nodes WHERE currency = ? ORDER BY added_at")) {
            ps.setString(1, currency);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(seedNodeFromRow(rs));
                }
            }
        }
        return result;
    }

    /**
     * Every seed/discovered peer for this currency, best score first --
     * this is what a caller should actually use to decide who to try
     * connecting to, rather than listSeedNodes()'s insertion order.
     * Ties (very common right after mergeSeedsFromResource(), when
     * every entry starts at the same default score) are broken
     * randomly rather than always falling back to added_at order, so a
     * fresh wallet doesn't deterministically hammer the same first
     * default seed every single run.
     */
    public List<SeedNode> getBestSeedNodes(String currency) throws SQLException {
        List<SeedNode> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM seed_nodes WHERE currency = ? ORDER BY score DESC, RANDOM()")) {
            ps.setString(1, currency);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(seedNodeFromRow(rs));
                }
            }
        }
        return result;
    }

    private SeedNode seedNodeFromRow(ResultSet rs) throws SQLException {
        SeedNode s = new SeedNode();
        s.pubkeyB32 = rs.getString("pubkey_b32");
        s.ip = rs.getString("ip");
        s.port = rs.getInt("port");
        s.source = rs.getString("source");
        s.score = rs.getInt("score");
        s.consecutiveFailures = rs.getInt("consecutive_failures");
        s.lastAttemptAt = rs.getLong("last_attempt_at");
        s.lastSuccessAt = rs.getLong("last_success_at");
        return s;
    }

    private static final int SEED_SCORE_MAX = 100;
    private static final int SEED_SCORE_MIN = 0;
    private static final int SEED_SUCCESS_BONUS = 10;
    private static final int SEED_FAILURE_PENALTY = 15;

    /**
     * Records a successful connection -- raises score, resets the
     * consecutive-failure count. Deliberately never deletes a seed for
     * failing, no matter how many times: a bad stretch (this peer's own
     * downtime, a transient network hiccup on our end) just sinks a
     * seed to the bottom of getBestSeedNodes()'s ordering, where it
     * naturally stops being tried while anything better exists, without
     * ever losing the ability to recover if it comes back -- and
     * without ever risking a wallet left with zero seeds to bootstrap
     * from over what might have been a one-off failure.
     */
    public void recordSeedSuccess(String currency, String pubkeyB32, String ip) throws SQLException {
        long now = System.currentTimeMillis() / 1000;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE seed_nodes SET score = MIN(?, score + ?), consecutive_failures = 0, "
                        + "last_attempt_at = ?, last_success_at = ? "
                        + "WHERE currency = ? AND pubkey_b32 = ? AND ip = ?")) {
            ps.setInt(1, SEED_SCORE_MAX);
            ps.setInt(2, SEED_SUCCESS_BONUS);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.setString(5, currency);
            ps.setString(6, pubkeyB32);
            ps.setString(7, ip);
            ps.executeUpdate();
        }
    }

    /** Records a failed connection attempt -- lowers score, never
     *  deletes. See recordSeedSuccess()'s own comment for why. */
    public void recordSeedFailure(String currency, String pubkeyB32, String ip) throws SQLException {
        long now = System.currentTimeMillis() / 1000;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE seed_nodes SET score = MAX(?, score - ?), consecutive_failures = consecutive_failures + 1, "
                        + "last_attempt_at = ? "
                        + "WHERE currency = ? AND pubkey_b32 = ? AND ip = ?")) {
            ps.setInt(1, SEED_SCORE_MIN);
            ps.setInt(2, SEED_FAILURE_PENALTY);
            ps.setLong(3, now);
            ps.setString(4, currency);
            ps.setString(5, pubkeyB32);
            ps.setString(6, ip);
            ps.executeUpdate();
        }
    }

    // ── Transaction history ─────────────────────────────────────────────
    // Scoped by both wallet_name and currency (unlike seed_nodes, which
    // is currency-only and shared across every wallet of that currency)
    // -- this is inherently per-wallet: what counts as "this wallet
    // received a payment" is meaningless without knowing which wallet.

    /** Height given to a transaction the wallet has made but not yet seen in a
     *  block. It sorts above every real height (so it shows first in Recent
     *  activity) and is replaced by the real record once the block is scanned. */
    public static final int PENDING_HEIGHT = 2_000_000_000;

    public static class ActivityRecord {
        public String txid, kind, label;
        public int height;
        public long amount; // signed: positive = net gain to this wallet, negative = net cost (a send, or a covenant operation's fee)
        public long recordedAt;
        // History-screen fields (Bob Wallet's export columns). type is
        // RECEIVE/SEND or the covenant name (OPEN, BID, ... FINALIZE);
        // fee is only non-zero when this wallet paid it; domains is a
        // comma-separated list of plaintext names; blockTime is the block
        // header's timestamp in seconds (0 = not yet mined).
        public String type = "";
        public String domains = "";
        public long fee;
        public long blockTime;
    }

    /**
     * Records one transaction's effect on a wallet, keyed by txid. A second
     * record for the same transaction REPLACES the first: a re-scanned block
     * can classify a transaction more completely than the first pass did, and
     * a transaction recorded as pending (height PENDING_HEIGHT) is replaced by
     * its confirmed record once its block is scanned.
     */
    public void recordActivity(String walletName, String currency, ActivityRecord r) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO transaction_history "
                        + "(wallet_name, currency, txid, height, kind, label, amount, recorded_at, "
                        + "tx_type, domains, fee, block_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, walletName);
            ps.setString(2, currency);
            ps.setString(3, r.txid);
            ps.setInt(4, r.height);
            ps.setString(5, r.kind);
            ps.setString(6, r.label);
            ps.setLong(7, r.amount);
            ps.setLong(8, r.recordedAt);
            ps.setString(9, r.type == null ? "" : r.type);
            ps.setString(10, r.domains == null ? "" : r.domains);
            ps.setLong(11, r.fee);
            ps.setLong(12, r.blockTime);
            ps.executeUpdate();
        }
    }

    /** Most recent activity first -- what the dashboard's "Recent
     *  activity" panel reads directly. Ordered by height, not
     *  recorded_at: height is the authoritative chain order a person
     *  actually cares about, while recorded_at is only this local
     *  machine's clock at whatever moment it happened to sync that
     *  block, which is not the same thing (a resumed sync after days
     *  offline would recordActivity() a whole backlog with
     *  recorded_at all clustered around "just now"). */
    public List<ActivityRecord> loadRecentActivity(String walletName, String currency, int limit) throws SQLException {
        return queryActivity(walletName, currency, limit);
    }

    /** Every transaction the wallet has ever had, newest first (History screen). */
    public List<ActivityRecord> loadAllActivity(String walletName, String currency) throws SQLException {
        return queryActivity(walletName, currency, Integer.MAX_VALUE);
    }

    private List<ActivityRecord> queryActivity(String walletName, String currency, int limit) throws SQLException {
        List<ActivityRecord> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM transaction_history WHERE wallet_name = ? AND currency = ? "
                        + "ORDER BY height DESC, recorded_at DESC LIMIT ?")) {
            ps.setString(1, walletName);
            ps.setString(2, currency);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ActivityRecord r = new ActivityRecord();
                    r.txid = rs.getString("txid");
                    r.height = rs.getInt("height");
                    r.kind = rs.getString("kind");
                    r.label = rs.getString("label");
                    r.amount = rs.getLong("amount");
                    r.recordedAt = rs.getLong("recorded_at");
                    r.type = rs.getString("tx_type");
                    r.domains = rs.getString("domains");
                    r.fee = rs.getLong("fee");
                    r.blockTime = rs.getLong("block_time");
                    result.add(r);
                }
            }
        }
        return result;
    }

    /**
     * The one legitimate way this table's contents ever get removed:
     * a chain reorg has invalidated every block above targetHeight, so
     * whatever this wallet recorded from those blocks needs to go too
     * -- not because the recorded data was wrong, but because the
     * transaction it described is no longer confirmed on the canonical
     * chain at all. Deliberately currency-scoped, not wallet-scoped: a
     * reorg invalidates blocks for every wallet watching that chain at
     * once, the same way the node's own header rollback isn't scoped
     * to "which peer noticed." If the same (or a conflicting)
     * transaction later confirms again on the honest chain, a normal
     * recordActivity() call adds it back -- this method only ever
     * deletes, it never rewrites a row in place.
     */
    public void deleteActivityAboveHeight(String currency, int targetHeight) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM transaction_history WHERE currency = ? AND height > ? AND height < 2000000000")) {
            ps.setString(1, currency);
            ps.setInt(2, targetHeight);
            ps.executeUpdate();
        }
    }



    // ── Currencies ───────────────────────────────────────────────────────
    // Simple string constants, not an enum -- deliberately, so adding a
    // future currency never requires touching this class's own type
    // system, just a new constant and whatever currency-specific code
    // actually needs it elsewhere.

    public static final String CURRENCY_HNS = "HNS";

    public static class OpenedWallet {
        public byte[] seed;
        public int accountIndex;
        public byte[] salt; // needed by callers to also decrypt pending-bid fields, which use the same wallet salt
        public String currency;
    }

    public static class WalletInfo {
        public final String name;
        public final String currency;
        WalletInfo(String name, String currency) { this.name = name; this.currency = currency; }
    }

    /** Creates a new wallet entry: encrypts the given seed under the
     *  given passphrase with a freshly-generated salt. The mnemonic
     *  itself is never stored -- only the derived 64-byte seed, matching
     *  how HDKey.masterFromSeed() consumes it; regenerating the mnemonic
     *  from the seed isn't possible or needed (the person's own written-
     *  down mnemonic, not this file, is the real backup of record).
     *
     *  Currency is fixed here, at creation, and never changes for this
     *  wallet afterward -- one wallet is one name, one seed, one
     *  currency, matching how Bob Wallet's own multiple-restored-
     *  wallets pattern already works today: adding a second currency
     *  later means creating another wallet entry, the same action as
     *  creating any other wallet, not something an existing wallet
     *  grows into. */
    public void createWallet(String name, String currency, byte[] seed, String passphrase, int accountIndex) throws SQLException {
        createWallet(name, currency, seed, passphrase, accountIndex, 0);
    }

    /** As above, also recording where block scanning should begin:
     *  scanFromTime 0 = from the start of the chain (safest), -1 = a
     *  brand-new wallet (start at the current tip), >0 = unix seconds
     *  the wallet is known to date from (scanning starts a day earlier
     *  to allow for header-timestamp skew). */
    public void createWallet(String name, String currency, byte[] seed, String passphrase, int accountIndex,
                             long scanFromTime) throws SQLException {
        byte[] salt = WalletCrypto.generateSalt();
        byte[] encryptedSeed = WalletCrypto.encrypt(seed, passphrase, salt);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO wallets (name, currency, encrypted_seed, seed_salt, created_at, account_index) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, name);
            ps.setString(2, currency);
            ps.setBytes(3, encryptedSeed);
            ps.setBytes(4, salt);
            ps.setLong(5, System.currentTimeMillis() / 1000);
            ps.setInt(6, accountIndex);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO wallet_scan_state (wallet_name, scanned_height, birth_time) VALUES (?,?,?)")) {
            ps.setString(1, name);
            ps.setInt(2, -1);
            ps.setLong(3, scanFromTime);
            ps.executeUpdate();
        }
    }

    /** {scannedHeight, birthTime} for a wallet, or null for a wallet
     *  created before scan progress was tracked (it is treated as
     *  already scanned up to its stored headers). */
    public long[] loadScanState(String walletName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT scanned_height, birth_time FROM wallet_scan_state WHERE wallet_name = ?")) {
            ps.setString(1, walletName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new long[]{rs.getInt("scanned_height"), rs.getLong("birth_time")};
            }
        }
    }

    public void saveScannedHeight(String walletName, int scannedHeight) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO wallet_scan_state (wallet_name, scanned_height, birth_time) VALUES (?,?,0) "
                        + "ON CONFLICT(wallet_name) DO UPDATE SET scanned_height = excluded.scanned_height")) {
            ps.setString(1, walletName);
            ps.setInt(2, scannedHeight);
            ps.executeUpdate();
        }
    }

    /** Every wallet in this file, across every currency -- the real
     *  source of what populates the top bar's own wallet picker
     *  dropdown. Each entry carries its own currency alongside its
     *  name (e.g. "scott1" / HNS) specifically so that dropdown can
     *  render "scott1 - Handshake (HNS)" without a second query per
     *  wallet. */
    public List<WalletInfo> listWallets() throws SQLException {
        List<WalletInfo> result = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name, currency FROM wallets ORDER BY created_at")) {
            while (rs.next()) result.add(new WalletInfo(rs.getString("name"), rs.getString("currency")));
        }
        return result;
    }

    /** Opens a wallet, decrypting its seed under the given passphrase --
     *  throws WalletCrypto.WrongPassphraseException directly (not a
     *  generic failure) if the passphrase is wrong, since that's a real,
     *  expected, distinct case a caller (eventually the GUI) needs to
     *  handle differently from "this wallet doesn't exist" or a genuine
     *  I/O error. */
    public OpenedWallet openWallet(String name, String passphrase) throws SQLException, WalletCrypto.WrongPassphraseException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT encrypted_seed, seed_salt, account_index, currency FROM wallets WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("No wallet named \"" + name + "\"");
                byte[] encryptedSeed = rs.getBytes("encrypted_seed");
                byte[] salt = rs.getBytes("seed_salt");
                OpenedWallet result = new OpenedWallet();
                result.seed = WalletCrypto.decrypt(encryptedSeed, passphrase, salt);
                result.accountIndex = rs.getInt("account_index");
                result.salt = salt;
                result.currency = rs.getString("currency");
                return result;
            }
        }
    }

    /**
     * Confirms a passphrase is correct for an already-existing wallet,
     * without returning (or needing the caller to discard) its
     * decrypted seed. For re-authenticating an ALREADY-open
     * WalletSession after an inactivity auto-lock, where that
     * session's own in-memory state (kept live by a background sync
     * loop the whole time it was locked -- see WalletLockTimer's own
     * comment on why locking never stops that) should just be shown
     * again, not rebuilt from scratch via a second, redundant
     * WalletSession.open(). Simply calls openWallet() and discards its
     * result -- WalletCrypto.decrypt() is the real, unavoidable work
     * either way, so there's no cheaper primitive underneath this
     * worth calling instead.
     */
    public void verifyPassphrase(String name, String passphrase) throws SQLException, WalletCrypto.WrongPassphraseException {
        openWallet(name, passphrase);
    }

    // ── Address manager state ───────────────────────────────────────────

    public void saveAddressState(String walletName, int chain, int highestUsedIndex) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO address_state (wallet_name, chain, highest_used_index) VALUES (?,?,?) "
                        + "ON CONFLICT(wallet_name, chain) DO UPDATE SET highest_used_index = excluded.highest_used_index")) {
            ps.setString(1, walletName);
            ps.setInt(2, chain);
            ps.setInt(3, highestUsedIndex);
            ps.executeUpdate();
        }
    }

    /** Returns highest-used-index per chain (external=0, internal=1),
     *  or -1 for a chain with no saved state yet (a fresh wallet). */
    public int loadHighestUsedIndex(String walletName, int chain) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT highest_used_index FROM address_state WHERE wallet_name = ? AND chain = ?")) {
            ps.setString(1, walletName);
            ps.setInt(2, chain);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("highest_used_index") : -1;
            }
        }
    }

    // ── UTXOs ────────────────────────────────────────────────────────────

    public void saveUtxo(String walletName, WalletUtxoSet.Utxo utxo) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO utxos (wallet_name, txid, output_index, value, addr_hash, height, spent, spent_at_height, cov_type, name_hash) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(wallet_name, txid, output_index) DO UPDATE SET "
                        + "spent = excluded.spent, spent_at_height = excluded.spent_at_height")) {
            ps.setString(1, walletName);
            ps.setString(2, hex(utxo.txid));
            ps.setInt(3, utxo.outputIndex);
            ps.setLong(4, utxo.value);
            ps.setString(5, hex(utxo.addrHash));
            ps.setInt(6, utxo.height);
            ps.setInt(7, utxo.spent ? 1 : 0);
            if (utxo.spentAtHeight != null) ps.setInt(8, utxo.spentAtHeight); else ps.setNull(8, Types.INTEGER);
            ps.setInt(9, utxo.covenantType);
            if (utxo.nameHash != null) ps.setString(10, hex(utxo.nameHash)); else ps.setNull(10, Types.VARCHAR);
            ps.executeUpdate();
        }
    }

    public List<WalletUtxoSet.Utxo> loadUtxos(String walletName) throws SQLException {
        List<WalletUtxoSet.Utxo> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM utxos WHERE wallet_name = ?")) {
            ps.setString(1, walletName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rowToUtxo(rs));
                }
            }
        }
        return result;
    }

    private WalletUtxoSet.Utxo rowToUtxo(ResultSet rs) throws SQLException {
        // WalletUtxoSet.Utxo's constructor is package-private and its
        // fields besides spent/spentAtHeight are final by design (see
        // its own comment) -- reflection is used here specifically to
        // respect that immutability rather than loosen it just to make
        // storage convenient.
        try {
            java.lang.reflect.Constructor<WalletUtxoSet.Utxo> ctor = WalletUtxoSet.Utxo.class.getDeclaredConstructor(
                    byte[].class, int.class, long.class, byte[].class, int.class);
            ctor.setAccessible(true);
            WalletUtxoSet.Utxo utxo = ctor.newInstance(
                    unhex(rs.getString("txid")), rs.getInt("output_index"), rs.getLong("value"),
                    unhex(rs.getString("addr_hash")), rs.getInt("height"));
            utxo.spent = rs.getInt("spent") != 0;
            int spentAtHeight = rs.getInt("spent_at_height");
            utxo.spentAtHeight = rs.wasNull() ? null : spentAtHeight;
            utxo.covenantType = rs.getInt("cov_type");
            String nh = rs.getString("name_hash");
            utxo.nameHash = nh == null ? null : unhex(nh);
            return utxo;
        } catch (Exception e) {
            throw new SQLException("Failed to reconstruct UTXO from storage", e);
        }
    }

    // ── Pending bids ─────────────────────────────────────────────────────

    public void savePendingBid(String walletName, WalletBidManager.PendingBid bid, String passphrase, byte[] walletSalt)
            throws SQLException {
        byte[] valueBytes = longToBytes(bid.value);
        byte[] encryptedValue = WalletCrypto.encrypt(valueBytes, passphrase, walletSalt);
        byte[] encryptedNonce = WalletCrypto.encrypt(bid.nonce, passphrase, walletSalt);

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pending_bids (wallet_name, name_hash, name, encrypted_value, lockup, encrypted_nonce, "
                        + "blind, open_height, bid_addr_hash, bid_txid, bid_output_index) VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(wallet_name, name_hash) DO UPDATE SET bid_txid = excluded.bid_txid, "
                        + "bid_output_index = excluded.bid_output_index")) {
            ps.setString(1, walletName);
            ps.setString(2, hex(bid.nameHash));
            ps.setString(3, bid.name);
            ps.setBytes(4, encryptedValue);
            ps.setLong(5, bid.lockup);
            ps.setBytes(6, encryptedNonce);
            ps.setString(7, hex(bid.blind));
            ps.setInt(8, bid.openHeight);
            ps.setString(9, hex(bid.bidAddrHash));
            if (bid.bidTxid != null) ps.setString(10, hex(bid.bidTxid)); else ps.setNull(10, Types.VARCHAR);
            ps.setInt(11, bid.bidOutputIndex);
            ps.executeUpdate();
        }
    }

    public List<WalletBidManager.PendingBid> loadPendingBids(String walletName, String passphrase, byte[] walletSalt)
            throws SQLException, WalletCrypto.WrongPassphraseException {
        List<WalletBidManager.PendingBid> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM pending_bids WHERE wallet_name = ?")) {
            ps.setString(1, walletName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    WalletBidManager.PendingBid bid = new WalletBidManager.PendingBid();
                    bid.nameHash = unhex(rs.getString("name_hash"));
                    bid.name = rs.getString("name");
                    bid.value = bytesToLong(WalletCrypto.decrypt(rs.getBytes("encrypted_value"), passphrase, walletSalt));
                    bid.lockup = rs.getLong("lockup");
                    bid.nonce = WalletCrypto.decrypt(rs.getBytes("encrypted_nonce"), passphrase, walletSalt);
                    bid.blind = unhex(rs.getString("blind"));
                    bid.openHeight = rs.getInt("open_height");
                    bid.bidAddrHash = unhex(rs.getString("bid_addr_hash"));
                    String bidTxidHex = rs.getString("bid_txid");
                    bid.bidTxid = bidTxidHex != null ? unhex(bidTxidHex) : null;
                    bid.bidOutputIndex = rs.getInt("bid_output_index");
                    result.add(bid);
                }
            }
        }
        return result;
    }

    public void removePendingBid(String walletName, byte[] nameHash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM pending_bids WHERE wallet_name = ? AND name_hash = ?")) {
            ps.setString(1, walletName);
            ps.setString(2, hex(nameHash));
            ps.executeUpdate();
        }
    }

    // ── Name state ───────────────────────────────────────────────────────

    public void saveNameState(String walletName, WalletNameState.Entry entry) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO name_state (wallet_name, name_hash, name, open_height, registered, "
                        + "renewal_height, owner_txid, owner_output_index) VALUES (?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(wallet_name, name_hash) DO UPDATE SET registered = excluded.registered, "
                        + "renewal_height = excluded.renewal_height, owner_txid = excluded.owner_txid, "
                        + "owner_output_index = excluded.owner_output_index")) {
            ps.setString(1, walletName);
            ps.setString(2, hex(entry.nameHash));
            ps.setString(3, entry.name);
            ps.setInt(4, entry.openHeight);
            ps.setInt(5, entry.registered ? 1 : 0);
            ps.setInt(6, entry.renewalHeight);
            if (entry.ownerTxid != null) ps.setString(7, hex(entry.ownerTxid)); else ps.setNull(7, Types.VARCHAR);
            ps.setInt(8, entry.ownerOutputIndex);
            ps.executeUpdate();
        }
    }

    public List<WalletNameState.Entry> loadNameState(String walletName) throws SQLException {
        List<WalletNameState.Entry> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM name_state WHERE wallet_name = ?")) {
            ps.setString(1, walletName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    WalletNameState.Entry e = new WalletNameState.Entry();
                    e.nameHash = unhex(rs.getString("name_hash"));
                    e.name = rs.getString("name");
                    e.openHeight = rs.getInt("open_height");
                    e.registered = rs.getInt("registered") != 0;
                    e.renewalHeight = rs.getInt("renewal_height");
                    String ownerTxidHex = rs.getString("owner_txid");
                    e.ownerTxid = ownerTxidHex != null ? unhex(ownerTxidHex) : null;
                    e.ownerOutputIndex = rs.getInt("owner_output_index");
                    result.add(e);
                }
            }
        }
        return result;
    }

    // ── Headers (shared across every wallet of the same currency, not
    //    across the whole file -- two Handshake wallets share one
    //    Handshake header chain; a future Bitcoin wallet needs its own,
    //    entirely separate chain, not a shared row space with this
    //    one) ────────────────────────────────────────────────────────

    public void saveHeader(String currency, int height, byte[] rawHeader) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO headers (currency, height, raw_header) VALUES (?,?,?) "
                        + "ON CONFLICT(currency, height) DO UPDATE SET raw_header = excluded.raw_header")) {
            ps.setString(1, currency);
            ps.setInt(2, height);
            ps.setBytes(3, rawHeader);
            ps.executeUpdate();
        }
    }

    public int loadHighestHeaderHeight(String currency) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT MAX(height) AS h FROM headers WHERE currency = ?")) {
            ps.setString(1, currency);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("h") : -1; // 0 if a row exists with NULL max (no rows), -1 otherwise handled by getInt's own default
            }
        }
    }

    public byte[] loadHeader(String currency, int height) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT raw_header FROM headers WHERE currency = ? AND height = ?")) {
            ps.setString(1, currency);
            ps.setInt(2, height);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes("raw_header") : null;
            }
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    private static byte[] longToBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (i * 8));
        return b;
    }

    private static long bytesToLong(byte[] b) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (b[i] & 0xFFL) << (i * 8);
        return v;
    }
}