package handshake.wallet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * A standalone, minimal P2P client connection to a Handshake node --
 * real hsd, or this project's own easy-handshake-node, since both speak
 * the same real wire protocol. Deliberately NOT built by reusing the
 * node project's own PeerConnection class, which is a private inner
 * class of ChainSync tightly coupled to a full validating node's own
 * state (ChainDB, mempool, block relay, peer scoring) that a wallet has
 * no use for and shouldn't need to drag in.
 *
 * Instead, this reproduces just the pieces a lightweight client
 * actually needs -- the Brontide handshake (via BrontideState, reused
 * directly, since it's already pure and already verified), the exact
 * wire framing, and the VERSION/VERACK handshake -- adapted directly
 * from ChainSync's own, already-verified implementation rather than
 * rewritten from scratch. Several details here were hard-won corrections
 * against real hsd behavior in that original code (the real hsd
 * VersionPacket layout including its NetAddress field, a version/verack
 * ordering bug where consuming only whichever message arrived first
 * left the other one stuck unread in the stream) -- preserved exactly,
 * not re-derived.
 *
 * Identity: unlike a full node, this wallet doesn't need a STABLE P2P
 * identity that other peers recognize across reconnects -- nothing
 * depends on being able to find this wallet again later the way full
 * nodes find each other for ongoing sync. So each connection uses a
 * fresh, ephemeral, randomly-generated secp256k1 keypair by default
 * (a caller can still supply their own if they want one identity shared
 * across several simultaneous connections in one session).
 */
public class WalletPeerConnection implements AutoCloseable {

    // Message type values verified directly against real hsd's own
    // lib/net/packets.js earlier in this project (see the node's own
    // ChainSync.java for that verification) -- not re-derived here,
    // since it's the same real protocol regardless of which side of a
    // connection is speaking it.
    public static final int MSG_VERSION     = 0;
    public static final int MSG_VERACK      = 1;
    public static final int MSG_PING        = 2;
    public static final int MSG_PONG        = 3;
    public static final int MSG_GETADDR     = 4;
    public static final int MSG_ADDR        = 5;
    public static final int MSG_INV         = 6;
    public static final int MSG_GETDATA     = 7;
    public static final int MSG_NOTFOUND    = 8;
    public static final int MSG_GETBLOCKS   = 9;
    public static final int MSG_GETHEADERS  = 10;
    public static final int MSG_HEADERS     = 11;
    public static final int MSG_SENDHEADERS = 12;
    public static final int MSG_BLOCK       = 13;
    public static final int MSG_TX          = 14;
    public static final int MSG_REJECT      = 15;
    public static final int MSG_MEMPOOL     = 16;
    public static final int MSG_FILTERLOAD  = 17;
    public static final int MSG_FILTERADD   = 18;
    public static final int MSG_FILTERCLEAR = 19;
    public static final int MSG_MERKLEBLOCK = 20;

    private static final int MAGIC_MAINNET     = 0x5B6EF2D3;
    private static final int PROTOCOL_VERSION  = 3;
    private static final int NET_ADDRESS_SIZE  = 88;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int HANDSHAKE_TIMEOUT  = 10_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final BrontideState brontide;
    public final String ip;
    public final int port;

    public volatile String agent = "";
    public volatile int peerHeight = 0;
    public volatile int protocolVersion = 0;
    public volatile int services = 0;

    private WalletPeerConnection(Socket socket, InputStream in, OutputStream out,
                                 BrontideState brontide, String ip, int port) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.brontide = brontide;
        this.ip = ip;
        this.port = port;
    }

    /** Connects to a node given its full connection string, in the same
     *  "<base32pubkey>@<ip>:<port>" form real Handshake nodes print for
     *  themselves and expect to be referenced by (see NodeIdentity's own
     *  equivalent format on the node side). Generates a fresh, ephemeral
     *  local identity keypair for this connection. */
    public static WalletPeerConnection connect(String nodeUri, int ourHeight) throws Exception {
        byte[] ephemeralPriv = generateRandomPrivateKey();
        return connect(nodeUri, ourHeight, ephemeralPriv);
    }

    /** Same as connect(String, int), but with a caller-supplied local
     *  identity keypair -- useful for sharing one identity across
     *  several simultaneous connections in a single session, rather
     *  than a fresh one per connection. */
    public static WalletPeerConnection connect(String nodeUri, int ourHeight, byte[] localPrivateKey) throws Exception {
        int atIdx = nodeUri.indexOf('@');
        if (atIdx < 0) {
            throw new IllegalArgumentException(
                    "Expected \"<pubkey>@<ip>:<port>\", got: " + nodeUri);
        }
        String keyPart = nodeUri.substring(0, atIdx);
        String hostPart = nodeUri.substring(atIdx + 1);
        int colonIdx = hostPart.lastIndexOf(':');
        if (colonIdx < 0) {
            throw new IllegalArgumentException(
                    "Expected \"<pubkey>@<ip>:<port>\", got: " + nodeUri);
        }
        String ip = hostPart.substring(0, colonIdx);
        int port = Integer.parseInt(hostPart.substring(colonIdx + 1));
        byte[] remoteStaticPub = Base32.decode(keyPart);
        if (remoteStaticPub.length != 33) {
            throw new IllegalArgumentException(
                    "Decoded public key is " + remoteStaticPub.length + " bytes, expected 33: " + keyPart);
        }
        return connect(ip, port, remoteStaticPub, ourHeight, localPrivateKey);
    }

    /** Connects given the target's IP, port, and raw 33-byte compressed
     *  Brontide public key directly, without needing the combined
     *  "pubkey@ip:port" string form. */
    public static WalletPeerConnection connect(String ip, int port, byte[] remoteStaticPub,
                                               int ourHeight, byte[] localPrivateKey) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(HANDSHAKE_TIMEOUT);

        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        BrontideState brontide = new BrontideState(localPrivateKey, remoteStaticPub);

        // Act 1
        byte[] act1 = brontide.genActOne();
        out.write(act1);
        out.flush();

        // Act 2
        byte[] act2 = readExact(in, BrontideState.ACT_TWO_SIZE);
        if (act2 == null) {
            socket.close();
            throw new IOException("Incomplete or missing Act 2 from " + ip + ":" + port);
        }
        brontide.recvActTwo(act2);

        // Act 3
        byte[] act3 = brontide.genActThree();
        out.write(act3);
        out.flush();

        socket.setSoTimeout(DEFAULT_READ_TIMEOUT_MS);

        WalletPeerConnection conn = new WalletPeerConnection(socket, in, out, brontide, ip, port);
        conn.doVersionHandshake(ourHeight);
        return conn;
    }

    private void doVersionHandshake(int ourHeight) throws Exception {
        sendVersion(ourHeight);

        // Both VERSION and VERACK must be seen before the handshake is
        // complete -- not just whichever arrives first. See this
        // class's own top comment: an earlier version of this exact
        // logic (in the node project) broke on the first message seen,
        // which could leave the peer's own VERSION unconsumed in the
        // stream if their VERACK happened to arrive first, corrupting
        // whatever request was issued right after.
        boolean versionReceived = false;
        boolean verackReceived = false;
        long deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            if (versionReceived && verackReceived) return;
            byte[] msg = readMessage(HANDSHAKE_TIMEOUT);
            if (msg == null) throw new IOException("Handshake timeout waiting for a message from " + ip);
            int type = getMessageType(msg);
            if (type == MSG_VERSION) {
                parseVersion(Arrays.copyOfRange(msg, 9, msg.length));
                sendMessage(MSG_VERACK, new byte[0]);
                versionReceived = true;
            } else if (type == MSG_VERACK) {
                verackReceived = true;
            }
        }
        throw new IOException("Handshake timeout with " + ip
                + " (version=" + versionReceived + " verack=" + verackReceived + ")");
    }

    private void sendVersion(int ourHeight) throws Exception {
        // Real hsd VersionPacket layout: version(4) + services(4) +
        // hi_services(4) + time(8) + NetAddress(88) + nonce(8) +
        // agentLen(1) + agent(N) + height(4) + noRelay(1).
        if (ourHeight < 0) ourHeight = 0;
        String userAgent = "/easy-handshake-wallet:1.0.0/";
        byte[] agentBytes = userAgent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + 4 + 4 + 8 + NET_ADDRESS_SIZE + 8 + 1 + agentBytes.length + 4 + 1];
        int pos = 0;
        pos = writeLE32(payload, pos, PROTOCOL_VERSION);
        pos = writeLE32(payload, pos, 0);  // services -- a wallet offers none
        pos = writeLE32(payload, pos, 0);  // hi_services
        pos = writeLE64(payload, pos, System.currentTimeMillis() / 1000);
        pos = writeNetAddress(payload, pos, ip, port, new byte[33]); // all-zero key, matching real hsd's own convention here
        pos = writeLE64(payload, pos, 0L); // nonce
        payload[pos++] = (byte) agentBytes.length;
        System.arraycopy(agentBytes, 0, payload, pos, agentBytes.length);
        pos += agentBytes.length;
        pos = writeLE32(payload, pos, ourHeight);
        payload[pos] = 1; // noRelay: a wallet doesn't need transactions relayed to it unless it asked via FILTERLOAD

        sendMessage(MSG_VERSION, payload);
    }

    private static int writeNetAddress(byte[] buf, int pos, String ip, int port, byte[] brontideKey) {
        pos = writeLE64(buf, pos, 0L);
        pos = writeLE32(buf, pos, 0);
        pos = writeLE32(buf, pos, 0);
        buf[pos++] = 0;
        pos += 10;
        buf[pos++] = (byte) 0xFF;
        buf[pos++] = (byte) 0xFF;
        String[] octets = ip.split("\\.");
        for (int i = 0; i < 4; i++) {
            buf[pos + i] = octets.length == 4 ? (byte) Integer.parseInt(octets[i]) : 0;
        }
        pos += 4;
        pos += 20;
        buf[pos++] = (byte) port;
        buf[pos++] = (byte) (port >> 8);
        if (brontideKey != null && brontideKey.length == 33) {
            System.arraycopy(brontideKey, 0, buf, pos, 33);
        }
        pos += 33;
        return pos;
    }

    private void parseVersion(byte[] msg) {
        try {
            if (msg.length >= 8) {
                protocolVersion = (int) readLE32(msg, 0);
                services = (int) readLE32(msg, 4);
            }
            int pos = 4 + 4 + 4 + 8 + NET_ADDRESS_SIZE + 8; // = 116, start of agentLen
            if (msg.length <= pos) return;
            int agentLen = msg[pos] & 0xFF;
            pos++;
            if (msg.length >= pos + agentLen) {
                agent = new String(msg, pos, agentLen, java.nio.charset.StandardCharsets.UTF_8);
                pos += agentLen;
            }
            if (msg.length >= pos + 4) {
                peerHeight = (int) readLE32(msg, pos);
            }
        } catch (Exception ignored) {}
    }

    /** GETHEADERS payload: locator_count(varint) + hashes[] + stop_hash(32).
     *  No leading version field -- confirmed directly against real hsd
     *  source in the node project (see ChainSync's own comment on this
     *  exact point, and the real bug an earlier, wrong assumption here
     *  caused). */
    public void sendGetHeaders(java.util.List<byte[]> locator) throws Exception {
        byte[] payload = new byte[9 + locator.size() * 32 + 32];
        int pos = writeVarintBytes(payload, 0, locator.size());
        for (byte[] hash : locator) {
            System.arraycopy(hash, 0, payload, pos, 32);
            pos += 32;
        }
        pos += 32; // stop hash = all zeros
        sendMessage(MSG_GETHEADERS, Arrays.copyOf(payload, pos));
    }

    public void sendGetData(java.util.List<byte[]> hashes, int itemType) throws Exception {
        byte[] payload = new byte[9 + hashes.size() * 36];
        int pos = writeVarintBytes(payload, 0, hashes.size());
        for (byte[] hash : hashes) {
            pos = writeLE32(payload, pos, itemType);
            System.arraycopy(hash, 0, payload, pos, 32);
            pos += 32;
        }
        sendMessage(MSG_GETDATA, Arrays.copyOf(payload, pos));
    }

    /** Sends a FILTERLOAD message installing this wallet's bloom
     *  filter on the connection -- wire format (varint-length filter
     *  bytes + u32 hash-function count + u32 tweak + u8 update flag)
     *  verified directly against real bfilter's own BloomFilter.write()
     *  earlier this project (see the node's own ChainSync/BloomFilter
     *  comments for that verification). */
    public void sendFilterLoad(byte[] filterBytes, int hashFuncs, int tweak, int updateFlag) throws Exception {
        byte[] payload = new byte[varintSize(filterBytes.length) + filterBytes.length + 4 + 4 + 1];
        int pos = writeVarintBytes(payload, 0, filterBytes.length);
        System.arraycopy(filterBytes, 0, payload, pos, filterBytes.length);
        pos += filterBytes.length;
        pos = writeLE32(payload, pos, hashFuncs);
        pos = writeLE32(payload, pos, tweak);
        payload[pos] = (byte) updateFlag;
        sendMessage(MSG_FILTERLOAD, payload);
    }

    /** FILTERADD: adds one more element (an address hash, or a 36-byte
     *  outpoint) to the filter already installed on this connection. */
    public void sendFilterAdd(byte[] element) throws Exception {
        byte[] payload = new byte[varintSize(element.length) + element.length];
        int pos = writeVarintBytes(payload, 0, element.length);
        System.arraycopy(element, 0, payload, pos, element.length);
        sendMessage(MSG_FILTERADD, payload);
    }

    /** Parses a HEADERS message payload into individual 236-byte raw
     *  headers -- confirmed directly against real hsd source
     *  (lib/primitives/headers.js): Headers.write() emits ONLY the raw
     *  header, nothing else (no trailing tx-count byte the way a BLOCK
     *  message has one -- a Headers message has no transactions to
     *  count at all). Headers are packed back-to-back with nothing
     *  between them. Mirrors the node project's own, already
     *  hard-won-correct parseHeaders() exactly -- an earlier version of
     *  that logic incorrectly tried to skip a tx-count varint after
     *  every header, which happened to consume the first byte(s) of the
     *  NEXT header instead, meaning only the first header in any
     *  multi-header batch ever parsed correctly. */
    public static java.util.List<byte[]> parseHeadersMessage(byte[] msg) {
        java.util.List<byte[]> headers = new java.util.ArrayList<>();
        try {
            int pos = 9; // skip frame header (magic+type+length)
            int count = (int) readLE32VarintCompat(msg, pos);
            pos += varintSize(count);
            while (count-- > 0 && pos + 236 <= msg.length) {
                headers.add(Arrays.copyOfRange(msg, pos, pos + 236));
                pos += 236;
            }
        } catch (Exception ignored) {}
        return headers;
    }

    private static long readLE32VarintCompat(byte[] b, int pos) {
        int first = b[pos] & 0xFF;
        if (first < 0xFD) return first;
        if (first == 0xFD) return (b[pos+1] & 0xFFL) | ((b[pos+2] & 0xFFL) << 8);
        return readLE32(b, pos + 1);
    }

    public void sendGetAddr() throws Exception {
        sendMessage(MSG_GETADDR, new byte[0]);
    }

    /** A single peer entry parsed out of an ADDR response -- brontide
     *  key, IP, and port, in the same shape WalletSeedDatabase's own
     *  PeerAddress record uses, so a caller can hand one straight to
     *  connect() without any reshaping. */
    public record DiscoveredPeer(byte[] pubkey, String ip, int port) {}

    /**
     * Parses an ADDR message into individual peer entries, using the
     * real 88-byte-per-entry NetAddress format (matching writeNetAddress()
     * above exactly: time(8)+services(4)+hiServices(4)+addrType(1)+
     * raw(16)+reserved(20)+port(2)+key(33)) -- confirmed directly
     * against the node project's own ChainSync.writeNetAddress()/
     * PeerDiscovery.onAddrMessage(), which this mirrors rather than
     * re-derives.
     * <p>
     * Brontide-only, matching this whole project's standing policy
     * (see the node's PeerDiscovery class comment): an entry is only
     * ever returned if it carries a real, non-zero key AND is on port
     * 44806, the Brontide port. A keyless entry, or a keyed entry on
     * any other port, is silently skipped rather than returned as a
     * connectable candidate -- this wallet has no cleartext connection
     * code path to fall back to, by design, the same as the node.
     */
    public static java.util.List<DiscoveredPeer> parseAddrMessage(byte[] msg) {
        java.util.List<DiscoveredPeer> result = new java.util.ArrayList<>();
        try {
            int pos = 9; // skip frame header (magic+type+length)
            if (pos >= msg.length) return result;
            int count = msg[pos++] & 0xFF;
            for (int i = 0; i < count && pos + 88 <= msg.length; i++) {
                pos += 8 + 4 + 4; // time + services + hiServices
                pos += 1;         // addrType
                byte[] ipBytes = new byte[4];
                System.arraycopy(msg, pos + 12, ipBytes, 0, 4);
                pos += 16;
                pos += 20; // reserved
                int port = (msg[pos] & 0xFF) | ((msg[pos + 1] & 0xFF) << 8);
                pos += 2;
                byte[] keyBytes = Arrays.copyOfRange(msg, pos, pos + 33);
                pos += 33;

                if (port != 44806 || isAllZero(keyBytes)) continue;

                String ip = (ipBytes[0] & 0xFF) + "." + (ipBytes[1] & 0xFF)
                        + "." + (ipBytes[2] & 0xFF) + "." + (ipBytes[3] & 0xFF);
                result.add(new DiscoveredPeer(keyBytes, ip, port));
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static boolean isAllZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    public void sendMessage(int type, byte[] payload) throws Exception {
        byte[] frame = new byte[9 + payload.length];
        writeLE32(frame, 0, MAGIC_MAINNET);
        frame[4] = (byte) type;
        writeLE32(frame, 5, payload.length);
        System.arraycopy(payload, 0, frame, 9, payload.length);

        byte[] encrypted = brontide.encryptMessage(frame);
        synchronized (out) {
            out.write(encrypted);
            out.flush();
        }
    }

    public byte[] readMessage(int timeoutMs) throws Exception {
        socket.setSoTimeout(timeoutMs);

        byte[] header = readExact(in, 20);
        if (header == null) return null;

        int msgLen;
        try {
            msgLen = brontide.decryptLength(header);
        } catch (Exception e) {
            return null;
        }
        if (msgLen < 0 || msgLen > 4_000_000) return null;

        byte[] encPayload = readExact(in, msgLen + 16);
        if (encPayload == null) return null;

        byte[] frame;
        try {
            frame = brontide.decryptPayload(encPayload);
        } catch (Exception e) {
            return null;
        }
        if (frame == null || frame.length < 9) return null;

        long magic = readLE32(frame, 0) & 0xFFFFFFFFL;
        if (magic != (MAGIC_MAINNET & 0xFFFFFFFFL)) return null;

        return frame;
    }

    public static int getMessageType(byte[] msg) {
        if (msg == null || msg.length < 9) return -1;
        return msg[4] & 0xFF;
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    // ── Key generation ───────────────────────────────────────────────────────

    /** Generates a fresh, random secp256k1 private key for this
     *  connection's own ephemeral Brontide identity -- see this class's
     *  own top comment for why a wallet uses a fresh one per session
     *  rather than a persistent identity the way a full node does.
     *  Public so a caller managing several simultaneous connections
     *  (see connect(..., localPrivateKey)'s own comment) can generate
     *  one shared identity for all of them without duplicating this
     *  same curve-order-checked generation logic itself. */
    public static byte[] generateRandomPrivateKey() {
        SecureRandom random = new SecureRandom();
        byte[] candidate = new byte[32];
        while (true) {
            random.nextBytes(candidate);
            java.math.BigInteger value = new java.math.BigInteger(1, candidate);
            if (value.signum() != 0 && value.compareTo(Secp256k1.N) < 0) {
                return candidate;
            }
            // Astronomically unlikely to ever loop -- only fails for a
            // candidate that's zero or >= the curve order.
        }
    }

    // ── Wire primitives ──────────────────────────────────────────────────────

    private static byte[] readExact(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(buf, read, len - read);
            if (n < 0) return null;
            read += n;
        }
        return buf;
    }

    private static long readLE32(byte[] b, int pos) {
        return (b[pos] & 0xFFL) | ((b[pos+1] & 0xFFL) << 8)
                | ((b[pos+2] & 0xFFL) << 16) | ((b[pos+3] & 0xFFL) << 24);
    }

    private static int writeLE32(byte[] b, int pos, long v) {
        b[pos]   = (byte) v;   b[pos+1] = (byte)(v>>8);
        b[pos+2] = (byte)(v>>16); b[pos+3] = (byte)(v>>24);
        return pos + 4;
    }

    private static int writeLE64(byte[] b, int pos, long v) {
        for (int i = 0; i < 8; i++) b[pos+i] = (byte)(v >> (i*8));
        return pos + 8;
    }

    private static int writeVarintBytes(byte[] b, int pos, int v) {
        if (v < 0xFD) {
            b[pos] = (byte) v;
            return pos + 1;
        } else if (v <= 0xFFFF) {
            b[pos] = (byte) 0xFD;
            b[pos+1] = (byte) v;
            b[pos+2] = (byte) (v >> 8);
            return pos + 3;
        } else {
            b[pos] = (byte) 0xFE;
            writeLE32(b, pos + 1, v);
            return pos + 5;
        }
    }

    private static int varintSize(int v) {
        if (v < 0xFD) return 1;
        if (v <= 0xFFFF) return 3;
        return 5;
    }
}