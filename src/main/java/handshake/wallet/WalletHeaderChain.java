package handshake.wallet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintains the wallet's own local, independently-validated header
 * chain -- the foundation everything else in SPV sync depends on
 * (which blocks exist, what height they're at, and whether a MERKLEBLOCK
 * response's proof actually matches a header the wallet has itself
 * verified, not just trusted from whichever peer happened to answer).
 *
 * Genesis is seeded directly (see WalletGenesis's own comment for why
 * -- a peer's HEADERS response never includes it) and trusted without
 * a PoW check, matching how virtually every blockchain treats its own
 * genesis block: hardcoded and trusted by definition, not mined through
 * the normal process, so its hash is not expected to satisfy the usual
 * difficulty target the way a real, mined block's hash is (confirmed
 * directly here: the real genesis hash, read as a number, has bit-length
 * 255 -- essentially the maximum possible value, nowhere near a valid
 * low-hash PoW target). Every header after genesis gets the full
 * chainsFrom() + checkPOW() validation before being accepted.
 */
public class WalletHeaderChain {

    private final List<byte[]> headersByHeight = new ArrayList<>();
    private final Map<String, Integer> heightByHash = new HashMap<>();

    public WalletHeaderChain() {
        headersByHeight.add(WalletGenesis.GENESIS_HEADER);
        heightByHash.put(hex(WalletGenesis.GENESIS_HASH), 0);
    }

    public int tipHeight() {
        return headersByHeight.size() - 1;
    }

    public byte[] tipHash() {
        return HeaderUtil.hash(headersByHeight.get(tipHeight()));
    }

    public byte[] headerAt(int height) {
        if (height < 0 || height >= headersByHeight.size()) return null;
        return headersByHeight.get(height);
    }

    public Integer heightOf(byte[] hash) {
        return heightByHash.get(hex(hash));
    }

    /** Attempts to append one header to the tip of the chain --
     *  verifying it actually links to the current tip (chainsFrom) and
     *  that its own hash genuinely satisfies its own claimed difficulty
     *  target (checkPOW) before accepting it. Returns false, rejecting
     *  the header, if either check fails -- a peer sending a header
     *  that doesn't pass both is either malicious or confused, and
     *  either way its header must not be trusted. */
    public boolean tryAppend(byte[] header) {
        byte[] tip = headersByHeight.get(tipHeight());
        if (!HeaderUtil.chainsFrom(header, tip)) return false;
        if (!HeaderUtil.checkPOW(header)) return false;
        headersByHeight.add(header);
        heightByHash.put(hex(HeaderUtil.hash(header)), tipHeight());
        return true;
    }

    /** Appends as many headers from a HEADERS response as validly chain
     *  together starting from the current tip, in order -- stopping (not
     *  failing outright) at the first one that doesn't validate, since a
     *  batch that starts by genuinely extending the chain but includes
     *  one bad entry shouldn't discard the good prefix. Returns how many
     *  were actually appended. */
    public int appendAll(List<byte[]> headers) {
        int appended = 0;
        for (byte[] header : headers) {
            if (!tryAppend(header)) break;
            appended++;
        }
        return appended;
    }

    /** Builds a GETHEADERS locator from the current tip -- the last 10
     *  heights directly, then exponentially sparser going further back,
     *  capped at 32 entries. Reproduces the node project's own, already
     *  hard-won-correct algorithm exactly (see ChainSync.buildLocator's
     *  own comment: real hsd peers don't respond usefully to an empty
     *  locator on a fresh chain, which is why GENESIS_HASH is sent
     *  explicitly rather than leaving the locator empty when there's
     *  nothing else in it yet -- though here, since genesis is always
     *  already seeded, this chain is never actually "fresh" in that
     *  sense; the special case is kept anyway for the same defensive
     *  reasoning). */
    public List<byte[]> buildLocator() {
        List<byte[]> locator = new ArrayList<>();
        int tip = tipHeight();
        if (tip < 0) {
            locator.add(WalletGenesis.GENESIS_HASH);
            return locator;
        }
        int step = 1;
        for (int h = tip; h >= 0; h -= step) {
            byte[] header = headerAt(h);
            if (header != null) {
                locator.add(HeaderUtil.hash(header));
            }
            if (locator.size() > 10) step *= 2;
            if (locator.size() >= 32) break;
        }
        return locator;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}