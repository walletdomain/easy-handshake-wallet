package handshake.wallet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits a raw, full block into its individual transactions -- mirrors
 * the node project's own BlockProcessor.parseBlockTxs() exactly (block
 * format: header(236) + tx_count(varint) + txs[]), including a real bug
 * fix already made there: advancing by each transaction's true,
 * witness-data-aware totalSize, not a fixed guess per input that
 * silently misaligned every transaction after the first one whose real
 * witness size didn't match the guess.
 */
public final class WalletBlockParser {

    private WalletBlockParser() {}

    public static List<TxParser.ParsedTx> parseBlockTxs(byte[] rawBlock) {
        List<TxParser.ParsedTx> txs = new ArrayList<>();
        try {
            int pos = 236; // skip header
            if (pos >= rawBlock.length) return txs;

            int txCount = rawBlock[pos] & 0xFF;
            if (txCount < 0xFD) {
                pos++;
            } else if (txCount == 0xFD) {
                txCount = ((rawBlock[pos + 1] & 0xFF) | ((rawBlock[pos + 2] & 0xFF) << 8));
                pos += 3;
            } else {
                pos += 5;
            }

            for (int i = 0; i < txCount && pos < rawBlock.length; i++) {
                byte[] remaining = Arrays.copyOfRange(rawBlock, pos, rawBlock.length);
                TxParser.ParsedTx tx = TxParser.parse(remaining);
                if (tx == null) break;
                txs.add(tx);
                pos += tx.totalSize;
            }
        } catch (Exception ignored) {}
        return txs;
    }
}