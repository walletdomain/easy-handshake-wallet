package handshake.wallet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Requests a specific block from a connected, filtered peer and
 * processes whatever comes back -- since the peer has a filter
 * installed (via WalletPeerConnection.sendFilterLoad(), see the node
 * project's own serveFilteredBlock() for the server side this talks
 * to), the response is a MERKLEBLOCK plus one TX message per match,
 * rather than a full block.
 *
 * The critical trust step happens here, not on the server side: this
 * wallet does NOT simply believe whatever transactions a peer claims
 * matched. It independently reconstructs the merkle root from the
 * proof the peer sent and confirms it equals the block header's own
 * merkleRoot field -- a header this wallet has already validated
 * itself (chain linkage + real proof-of-work, see WalletHeaderChain).
 * A peer can choose not to show certain matching transactions, but it
 * cannot fabricate ones that weren't really in the block without also
 * forging a header that passes real PoW, which is the same guarantee
 * any SPV client's security ultimately rests on.
 */
public final class WalletBlockScanner {

    public static class ScanResult {
        public boolean success;
        public String error;
        public List<TxParser.ParsedTx> matchedTransactions = new ArrayList<>();
    }

    private WalletBlockScanner() {}

    /** One block, one request (kept for callers that want a single block). */
    public static ScanResult scanBlock(WalletPeerConnection conn, byte[] blockHash, byte[] expectedHeader,
                                       int timeoutMs) {
        try {
            conn.sendGetData(List.of(blockHash), 2); // itemType 2 = BLOCK
        } catch (Exception e) {
            ScanResult r = new ScanResult();
            r.error = "exception during block scan: " + e;
            return r;
        }
        return readOneBlock(conn, expectedHeader, timeoutMs);
    }

    /**
     * Pipelined scan: asks for all the given blocks in ONE request, then
     * reads the replies, which the peer sends back in request order. This
     * removes the per-block round trip that made scanning thousands of
     * blocks slow. Returns one result per block, in order, ending at the
     * first failure (that failed result is the last element) -- the caller
     * must treat only the leading successes as scanned, so a gap can never
     * be skipped over.
     */
    public static List<ScanResult> scanBlocks(WalletPeerConnection conn, List<byte[]> blockHashes,
                                              List<byte[]> expectedHeaders, int timeoutMs) {
        List<ScanResult> results = new ArrayList<>();
        try {
            conn.sendGetData(blockHashes, 2);
        } catch (Exception e) {
            ScanResult r = new ScanResult();
            r.error = "exception sending block request: " + e;
            results.add(r);
            return results;
        }
        for (int i = 0; i < blockHashes.size(); i++) {
            ScanResult r = readOneBlock(conn, expectedHeaders.get(i), timeoutMs);
            results.add(r);
            if (!r.success) break;
        }
        return results;
    }

    /** Message types a peer may legitimately interleave with replies:
     *  skipped, not treated as errors. */
    private static boolean isNoise(int type) {
        return type == WalletPeerConnection.MSG_INV || type == WalletPeerConnection.MSG_PING
                || type == WalletPeerConnection.MSG_PONG || type == WalletPeerConnection.MSG_ADDR
                || type == WalletPeerConnection.MSG_HEADERS || type == WalletPeerConnection.MSG_SENDHEADERS
                || type == WalletPeerConnection.MSG_VERACK;
    }

    private static byte[] readSkippingNoise(WalletPeerConnection conn, int timeoutMs) throws Exception {
        for (int skipped = 0; skipped < 50; skipped++) {
            byte[] msg = conn.readMessage(timeoutMs);
            if (msg == null) return null;
            if (!isNoise(WalletPeerConnection.getMessageType(msg))) return msg;
        }
        return null;
    }

    /** Reads and verifies the reply to one requested block: a
     *  MERKLEBLOCK plus one TX per match (see class comment). */
    private static ScanResult readOneBlock(WalletPeerConnection conn, byte[] expectedHeader, int timeoutMs) {
        ScanResult result = new ScanResult();
        try {
            byte[] msg = readSkippingNoise(conn, timeoutMs);
            if (msg == null) {
                result.error = "no response from peer";
                return result;
            }
            int type = WalletPeerConnection.getMessageType(msg);
            if (type != WalletPeerConnection.MSG_MERKLEBLOCK) {
                result.error = "expected MERKLEBLOCK, got message type " + type;
                return result;
            }

            byte[] payload = Arrays.copyOfRange(msg, 9, msg.length);
            MerkleProof.ParsedProof proof = MerkleProof.deserialize(payload);
            MerkleProof.ExtractedTree extracted = MerkleProof.extractTree(proof.totalTX, proof.hashes, proof.flags);

            if (extracted.failed) {
                result.error = "merkle proof extraction failed (malformed or inconsistent proof)";
                return result;
            }

            // The critical check: the reconstructed root must equal the
            // already-validated header's own merkle root, otherwise the
            // peer is lying or confused and nothing it sent is trusted.
            byte[] expectedRoot = HeaderUtil.merkleRoot(expectedHeader);
            if (!Arrays.equals(extracted.root, expectedRoot)) {
                result.error = "merkle root mismatch -- peer's proof does not match the validated header's own merkle root";
                return result;
            }

            for (int i = 0; i < extracted.matches.size(); i++) {
                byte[] txMsg = readSkippingNoise(conn, timeoutMs);
                if (txMsg == null || WalletPeerConnection.getMessageType(txMsg) != WalletPeerConnection.MSG_TX) {
                    result.error = "expected TX message " + (i + 1) + " of " + extracted.matches.size()
                            + " following MERKLEBLOCK, got something else";
                    return result;
                }
                byte[] rawTx = Arrays.copyOfRange(txMsg, 9, txMsg.length);
                TxParser.ParsedTx parsed = TxParser.parse(rawTx);

                // The tx must hash to the txid the merkle proof claimed
                // for this position.
                byte[] actualTxid = Blake2b.hash256(Arrays.copyOf(rawTx, parsed.baseSize));
                if (!Arrays.equals(actualTxid, extracted.matches.get(i))) {
                    result.error = "TX message " + (i + 1) + " does not match the txid claimed by the merkle proof";
                    return result;
                }
                result.matchedTransactions.add(parsed);
            }

            result.success = true;
            return result;
        } catch (Exception e) {
            result.error = "exception during block scan: " + e;
            return result;
        }
    }
}