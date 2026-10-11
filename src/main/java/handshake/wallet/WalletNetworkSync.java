package handshake.wallet;

import java.sql.SQLException;
import java.util.List;

/**
 * The actual call site tying WalletPeerDiscovery (find and confirm
 * reachable peers), WalletBloomFilter (build a filter from this
 * wallet's own watched addresses), and WalletSession.syncOnce() (do
 * the real header/balance/auction sync) together into one callable
 * pass -- the piece that was still missing after those three existed
 * independently, each already verified on its own.
 * <p>
 * Deliberately just ONE pass per call, not a scheduler or loop: a
 * caller (a GUI's periodic timer, a CLI driver, a test) decides how
 * often to call this, the same way ChainSync's own sync logic on the
 * node side is separate from Main.java's scheduling of it. Connections
 * are opened fresh for each call and always closed before returning --
 * matching WalletPeerConnection's own stated design (a wallet has no
 * need for a stable, reused P2P identity the way a full node does),
 * rather than this class trying to manage a longer-lived connection
 * pool on top of that.
 */
public final class WalletNetworkSync {

    private WalletNetworkSync() {}

    /** BIP37 update flag: BLOOM_UPDATE_ALL. A matched output's own
     *  outpoint gets added back into the filter automatically, so a
     *  later transaction spending it is also matched -- what a wallet
     *  actually needs to keep tracking its own UTXOs as they move,
     *  not just the addresses it started the session already knowing
     *  about. */
    private static final int BLOOM_UPDATE_ALL = 1;

    private static final double FILTER_FALSE_POSITIVE_RATE = 0.0001;

    /**
     * One full sync pass: confirms/discovers two reachable peers (see
     * WalletPeerDiscovery.confirmAndDiscover()), loads a bloom filter
     * built from this session's own currently-watched addresses onto
     * the first connection, and runs WalletSession.syncOnce() using
     * that as the filtered connection and the second as the unfiltered
     * one -- exactly the two-connection split WalletSession's own
     * class comment describes needing. Both connections are always
     * closed before this returns, success or failure.
     *
     * @throws NotEnoughPeersException if fewer than two connections
     *         could be confirmed at all -- callers should treat this
     *         distinctly from a genuine sync error (headerChain, utxo
     *         state, etc. are all untouched when this is thrown, since
     *         syncOnce() was never even reached).
     */
    public static WalletSession.SyncResult syncOnce(
            WalletStorage storage, WalletSession session, long feeRatePerByte) throws Exception {
        return syncOnce(storage, session, feeRatePerByte, null);
    }

    /** Same as above; "progress" (may be null) receives short human-readable
     *  status text as the pass moves through its stages. */
    public static WalletSession.SyncResult syncOnce(
            WalletStorage storage, WalletSession session, long feeRatePerByte,
            java.util.function.Consumer<String> progress) throws Exception {

        int ourHeight = session.headerChain.tipHeight();
        if (progress != null) progress.accept("Connecting to peers...");
        List<WalletPeerConnection> conns =
                WalletPeerDiscovery.confirmAndDiscover(storage, session.currency, ourHeight, 2, progress);
        try {
            if (conns.size() < 2) {
                throw new NotEnoughPeersException(
                        "Needed 2 confirmed peer connections, only got " + conns.size()
                                + " -- check network connectivity and the seed_nodes table.");
            }
            WalletPeerConnection filteredConn = conns.get(0);
            WalletPeerConnection unfilteredConn = conns.get(1);

            List<byte[]> watched = session.filterElements(); // addresses + unspent outpoints
            BloomFilter filter = WalletBloomFilter.build(watched, FILTER_FALSE_POSITIVE_RATE, Math.max(2000, watched.size() * 2));
            filteredConn.sendFilterLoad(filter.data(), filter.hashFuncs(), filter.tweak(), BLOOM_UPDATE_ALL);

            // Save progress periodically during a long restore, so closing
            // the wallet part-way does not throw away hours of scanning.
            session.setCheckpoint(() -> {
                try {
                    session.saveState(storage);
                } catch (java.sql.SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            return session.syncOnce(filteredConn, unfilteredConn, feeRatePerByte, progress);
        } finally {
            // Every connection confirmAndDiscover() returned gets
            // closed here, not just the two actually used above -- in
            // the maxConnections=2 case those are the same set, but
            // this stays correct even if a future caller asks for more.
            for (WalletPeerConnection conn : conns) {
                conn.close();
            }
        }
    }

    /** Thrown when confirmAndDiscover() couldn't confirm enough peers
     *  to even attempt a sync pass. Deliberately a distinct, named
     *  exception rather than a generic one or a null/empty-result
     *  return -- a caller (a GUI especially) needs to tell "the
     *  network seems to be the problem right now" apart from "sync
     *  itself failed partway through" to show a person something
     *  actually useful ("checking network..." vs. a raw error), and a
     *  checked SQLException or IOException from deep inside syncOnce()
     *  doesn't carry that distinction on its own. */
    public static class NotEnoughPeersException extends Exception {
        public NotEnoughPeersException(String message) {
            super(message);
        }
    }
}