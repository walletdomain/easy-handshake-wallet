package handshake.wallet;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Orchestrates the wallet's own peer discovery and connection
 * confirmation -- the missing piece between WalletStorage's persisted,
 * scored seed_nodes table and WalletPeerConnection's low-level ability
 * to actually connect. Mirrors the node project's own PeerDiscovery in
 * spirit (confirm candidates are reachable, discover more via ADDR,
 * build a scored, persisted address book over time) but scoped to what
 * a wallet actually needs: a small number of currently-good
 * connections (WalletSession.syncOnce() wants exactly two, filtered
 * and unfiltered), not a large concurrently-held outbound pool the way
 * a full node benefits from.
 * <p>
 * Brontide-only throughout, same as the node project: every connection
 * attempt requires a real, known 33-byte key up front (there's no
 * anonymous-initiator mode -- see WalletPeerConnection's own class
 * comment), and WalletPeerConnection.parseAddrMessage() only ever
 * returns entries that carry one, on port 44806. There is no cleartext
 * fallback anywhere in this class, by design.
 */
public final class WalletPeerDiscovery {

    private WalletPeerDiscovery() {}

    private static final int GETADDR_TIMEOUT_MS = 8_000;

    /**
     * Attempts a connection to every currently-known seed/peer for this
     * currency, in best-score-first order (see
     * WalletStorage.getBestSeedNodes()), stopping once maxConnections
     * have succeeded or candidates are exhausted. Every attempt is
     * scored via WalletStorage.recordSeedSuccess()/recordSeedFailure()
     * -- a failure never deletes an entry, only sinks its score, so a
     * seed that's temporarily unreachable can still recover and be
     * tried again later rather than being lost for good over what
     * might have been a one-off network hiccup.
     * <p>
     * Every connection that succeeds is also asked for its own peer
     * list (GETADDR/ADDR) before being handed back, and anything real
     * that comes back (a genuine key on port 44806) is persisted into
     * the same seed_nodes table with source="discovered", starting at
     * the same default score as any other new entry -- it becomes
     * "confirmed" the same way every other entry does, by actually
     * being connected to (and scored) on some future call, not by
     * being blindly trusted just because a peer gossiped about it.
     * <p>
     * All connections this method itself opens purely to ask for
     * GETADDR, but doesn't end up returning (i.e. anything beyond
     * maxConnections, or anything that fails after connecting but
     * before finishing its GETADDR round), are closed before this
     * returns -- the caller only ever has to manage exactly the
     * connections it gets back.
     *
     * @return up to maxConnections open, live connections, in the same
     *         best-score-first order they were attempted in. May
     *         return fewer than maxConnections (even zero) if not
     *         enough candidates succeeded.
     */
    public static List<WalletPeerConnection> confirmAndDiscover(
            WalletStorage storage, String currency, int ourHeight, int maxConnections)
            throws SQLException {
        return confirmAndDiscover(storage, currency, ourHeight, maxConnections, null);
    }

    /** How many seeds are dialed at the same time. Dialing them one after
     *  another meant every dead seed cost up to 10 s of connect timeout
     *  before the next was even tried -- with a list this size that left
     *  the wallet sitting on "Connecting..." for minutes. */
    private static final int PARALLEL_DIALS = 8;

    private static final class DialResult {
        final WalletStorage.SeedNode seed;
        final WalletPeerConnection conn;
        final String error;
        DialResult(WalletStorage.SeedNode seed, WalletPeerConnection conn, String error) {
            this.seed = seed; this.conn = conn; this.error = error;
        }
    }

    /** Same contract as above, but dials several seeds concurrently and
     *  reports progress ("progress" may be null). Storage is only ever
     *  touched from the calling thread. */
    public static List<WalletPeerConnection> confirmAndDiscover(
            WalletStorage storage, String currency, int ourHeight, int maxConnections,
            Consumer<String> progress)
            throws SQLException {

        List<WalletStorage.SeedNode> candidates = storage.getBestSeedNodes(currency);
        List<WalletPeerConnection> confirmed = new ArrayList<>();
        if (candidates.isEmpty()) {
            System.out.println("[Wallet] No seed nodes known -- nothing to connect to.");
            return confirmed;
        }
        byte[] sharedKey = WalletPeerConnection.generateRandomPrivateKey();
        AtomicBoolean done = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLEL_DIALS, candidates.size()), r -> {
            Thread t = new Thread(r, "wallet-dial");
            t.setDaemon(true);
            return t;
        });
        CompletionService<DialResult> cs = new ExecutorCompletionService<>(pool);
        int submitted = 0;
        for (WalletStorage.SeedNode seed : candidates) {
            byte[] pubkey;
            try {
                pubkey = Base32.decode(seed.pubkeyB32);
            } catch (Exception e) {
                continue; // malformed stored entry -- not the seed's fault, just skip it
            }
            final byte[] key = pubkey;
            cs.submit(() -> {
                if (done.get()) return new DialResult(seed, null, "skipped");
                try {
                    WalletPeerConnection c = WalletPeerConnection.connect(seed.ip, seed.port, key, ourHeight, sharedKey);
                    if (done.get()) { c.close(); return new DialResult(seed, null, "skipped"); }
                    return new DialResult(seed, c, null);
                } catch (Exception e) {
                    return new DialResult(seed, null, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            });
            submitted++;
        }

        int tried = 0;
        try {
            while (tried < submitted && confirmed.size() < maxConnections) {
                DialResult r;
                try {
                    r = cs.take().get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException ee) {
                    tried++;
                    continue;
                }
                tried++;
                if ("skipped".equals(r.error)) continue;
                if (r.conn == null) {
                    recordFailureQuietly(storage, currency, r.seed);
                    System.out.printf("[Wallet] Peer %s: %s%n", r.seed.ip, r.error);
                } else if (confirmed.size() >= maxConnections) {
                    r.conn.close(); // arrived after we already had enough
                } else {
                    recordSuccessQuietly(storage, currency, r.seed);
                    System.out.printf("[Wallet] Connected to peer %s%n", r.seed.ip);
                    discoverMore(r.conn, storage, currency);
                    confirmed.add(r.conn);
                }
                if (progress != null) {
                    progress.accept("Connecting to peers... " + confirmed.size() + "/" + maxConnections
                            + " (" + tried + " of " + submitted + " tried)");
                }
            }
        } finally {
            done.set(true);
            pool.shutdownNow();
            // Any connection that finished after we stopped waiting must not leak.
            Future<DialResult> f;
            while (tried < submitted && (f = cs.poll()) != null) {
                tried++;
                try {
                    DialResult r = f.get();
                    if (r.conn != null && !confirmed.contains(r.conn)) r.conn.close();
                } catch (Exception ignored) { }
            }
        }
        // The wallet needs a filtered and an unfiltered connection. With
        // a single-seed list (testing against our own node) there is
        // only one distinct peer, so open a second connection to it.
        if (confirmed.size() == 1 && maxConnections >= 2 && candidates.size() == 1) {
            WalletStorage.SeedNode seed = candidates.get(0);
            try {
                WalletPeerConnection c2 = WalletPeerConnection.connect(
                        seed.ip, seed.port, Base32.decode(seed.pubkeyB32), ourHeight,
                        WalletPeerConnection.generateRandomPrivateKey());
                System.out.printf("[Wallet] Opened second connection to %s%n", seed.ip);
                confirmed.add(c2);
            } catch (Exception e) {
                System.out.printf("[Wallet] Second connection to %s failed: %s%n", seed.ip, e.getMessage());
            }
        }
        return confirmed;
    }

    /**
     * Sends GETADDR on an already-connected peer and persists whatever
     * real (keyed, port-44806) entries come back in its ADDR response.
     * A failure here -- timeout, no ADDR reply, a malformed one -- does
     * NOT close or invalidate the connection itself; discovering more
     * peers is a bonus on top of an already-successful connection, not
     * a requirement of one.
     */
    private static void discoverMore(WalletPeerConnection conn, WalletStorage storage, String currency) {
        try {
            conn.sendGetAddr();
            long deadline = System.currentTimeMillis() + GETADDR_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                int remaining = (int) (deadline - System.currentTimeMillis());
                if (remaining <= 0) break;
                byte[] msg = conn.readMessage(remaining);
                if (msg == null) break;
                int type = WalletPeerConnection.getMessageType(msg);
                if (type == WalletPeerConnection.MSG_ADDR) {
                    for (WalletPeerConnection.DiscoveredPeer p : WalletPeerConnection.parseAddrMessage(msg)) {
                        try {
                            storage.addSeedNode(currency, Base32.encode(p.pubkey()), p.ip(), p.port(), "discovered");
                        } catch (SQLException ignored) {
                            // one bad insert (e.g. a genuine race with
                            // another concurrent writer) shouldn't abort
                            // the rest of this peer's ADDR batch
                        }
                    }
                    return;
                }
                // Anything else (PING, INV, etc.) while waiting -- keep
                // waiting for the ADDR reply rather than giving up on
                // the first unrelated message.
            }
        } catch (Exception ignored) {
            // See this method's own top comment -- never propagates.
        }
    }

    private static void recordSuccessQuietly(WalletStorage storage, String currency, WalletStorage.SeedNode seed) {
        try {
            storage.recordSeedSuccess(currency, seed.pubkeyB32, seed.ip);
        } catch (SQLException ignored) {
            // Scoring is a best-effort bookkeeping detail -- a failure
            // to record a success shouldn't undo the successful
            // connection this call is about to return.
        }
    }

    private static void recordFailureQuietly(WalletStorage storage, String currency, WalletStorage.SeedNode seed) {
        try {
            storage.recordSeedFailure(currency, seed.pubkeyB32, seed.ip);
        } catch (SQLException ignored) {}
    }
}