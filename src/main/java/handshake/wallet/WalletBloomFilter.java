package handshake.wallet;

import java.security.SecureRandom;
import java.util.List;

/**
 * Builds a BloomFilter sized for a given set of watched items and a
 * target false-positive rate, using the standard BIP-37 sizing formulas
 * -- these are universal, not Handshake-specific (the same math bcoin,
 * bitcoind, and every other BIP-37 implementation uses), unlike the
 * hash function itself (already verified elsewhere in this project to
 * match real bfilter's own MurmurHash3-based scheme).
 */
public final class WalletBloomFilter {

    // Same real policy limits already confirmed from real bfilter
    // source elsewhere in this project (see the node's own BloomFilter/
    // ChainSync comments): 36,000 bytes, 50 hash functions.
    private static final int MAX_FILTER_BYTES = 36_000;
    private static final int MAX_HASH_FUNCS = 50;

    private WalletBloomFilter() {}

    /** Builds a filter containing every given element, sized for the
     *  requested false-positive rate (e.g. 0.0001 for 1-in-10,000). */
    public static BloomFilter build(List<byte[]> elements, double falsePositiveRate) {
        return build(elements, falsePositiveRate, 0);
    }

    /** As above, but sized for at least "capacity" elements, leaving room
     *  for later FILTERADDs without the false-positive rate climbing. */
    public static BloomFilter build(List<byte[]> elements, double falsePositiveRate, int capacity) {
        int n = Math.max(Math.max(1, elements.size()), capacity);

        // Standard BIP-37 formulas.
        double sizeBits = Math.min(
                -1.0 / (Math.log(2) * Math.log(2)) * n * Math.log(falsePositiveRate),
                MAX_FILTER_BYTES * 8.0);
        int sizeBytes = Math.max(1, (int) Math.ceil(sizeBits / 8.0));

        double hashFuncsRaw = (sizeBytes * 8.0 / n) * Math.log(2);
        int hashFuncs = Math.max(1, Math.min((int) Math.round(hashFuncsRaw), MAX_HASH_FUNCS));

        int tweak = new SecureRandom().nextInt();

        BloomFilter filter = new BloomFilter(new byte[sizeBytes], hashFuncs, tweak);
        for (byte[] element : elements) {
            filter.add(element);
        }
        return filter;
    }
}