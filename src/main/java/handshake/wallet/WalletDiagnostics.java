package handshake.wallet;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writes a plain-text snapshot of what the wallet believes (coins, balance,
 * address window, scan position) to ~/.easy-handshake-wallet/wallet-debug.txt,
 * for comparing against another wallet or a block explorer. Contains
 * addresses and transaction ids but no keys or seed.
 */
public final class WalletDiagnostics {

    private WalletDiagnostics() {}

    public static File defaultFile() {
        return new File(new File(System.getProperty("user.home"), ".easy-handshake-wallet"), "wallet-debug.txt");
    }

    public static void write(WalletSession s) {
        try {
            Files.writeString(defaultFile().toPath(), report(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[Wallet] Could not write wallet-debug.txt: " + e.getMessage());
        }
    }

    public static String report(WalletSession s) {
        StringBuilder sb = new StringBuilder();
        List<WalletUtxoSet.Utxo> all = s.utxoSet.getAllUtxos();
        List<WalletUtxoSet.Utxo> unspent = new ArrayList<>();
        long spentTotal = 0;
        for (WalletUtxoSet.Utxo u : all) { if (u.spent) spentTotal += u.value; else unspent.add(u); }
        long balance = 0;
        for (WalletUtxoSet.Utxo u : unspent) balance += u.value;

        sb.append("Wallet debug snapshot ").append(Instant.now()).append('\n');
        sb.append("wallet:            ").append(s.walletName).append('\n');
        sb.append("header tip:        ").append(s.headerChain.tipHeight()).append('\n');
        sb.append("scanned height:    ").append(s.scannedHeight()).append('\n');
        sb.append("external addrs:    watching ").append(s.addressManager.watchedCount(HandshakeKeyPath.CHANGE_EXTERNAL))
                .append(", highest used index ").append(s.addressManager.highestUsed(HandshakeKeyPath.CHANGE_EXTERNAL)).append('\n');
        sb.append("change addrs:      watching ").append(s.addressManager.watchedCount(HandshakeKeyPath.CHANGE_INTERNAL))
                .append(", highest used index ").append(s.addressManager.highestUsed(HandshakeKeyPath.CHANGE_INTERNAL)).append('\n');
        sb.append("coins known:       ").append(all.size()).append(" (").append(unspent.size()).append(" unspent, ")
                .append(all.size() - unspent.size()).append(" spent)\n");
        sb.append("unspent, all coins:").append(' ').append(hns(balance)).append(" HNS (includes name coins)\n");
        sb.append("SPENDABLE balance: ").append(hns(s.utxoSet.getBalance())).append(" HNS  <- compare to Bob's spendable\n");
        sb.append("locked in auctions:").append(' ').append(hns(s.utxoSet.getLockedBalance())).append(" HNS\n");
        sb.append("carried by names:  ").append(hns(s.utxoSet.getNameCoinValue())).append(" HNS (not spendable)\n");
        sb.append("names owned now:   ").append(s.ownedNames().size()).append("  <- compare to Bob's Domains > Owned\n");
        sb.append("total ever received (spent coins): ").append(hns(spentTotal)).append(" HNS\n");
        sb.append("names ever recorded (old method, includes transferred-out): ").append(s.nameState.allOwnedNames().size()).append('\n');
        sb.append('\n');

        unspent.sort(Comparator.comparingLong((WalletUtxoSet.Utxo u) -> u.value).reversed());
        sb.append("Unspent coins, largest first (txid:index  value  height  chain/index):\n");
        for (WalletUtxoSet.Utxo u : unspent) {
            int[] ci = s.addressManager.findChainAndIndex(u.addrHash);
            sb.append(hex(u.txid)).append(':').append(u.outputIndex).append("  ")
                    .append(hns(u.value)).append("  ").append(u.height).append("  ")
                    .append(ci == null ? "?" : (ci[0] + "/" + ci[1])).append("  cov").append(u.covenantType).append('\n');
        }

        // Every coin ever seen, oldest first -- spent ones included -- so each
        // received transaction can be matched against another wallet's history
        // by txid.
        List<WalletUtxoSet.Utxo> everything = new ArrayList<>(all);
        everything.sort(Comparator.comparingInt((WalletUtxoSet.Utxo u) -> u.height));
        sb.append('\n').append("All coins ever seen, oldest first (txid:index  value  height  chain/index  status):\n");
        for (WalletUtxoSet.Utxo u : everything) {
            int[] ci = s.addressManager.findChainAndIndex(u.addrHash);
            sb.append(hex(u.txid)).append(':').append(u.outputIndex).append("  ")
                    .append(hns(u.value)).append("  ").append(u.height).append("  ")
                    .append(ci == null ? "?" : (ci[0] + "/" + ci[1])).append("  ")
                    .append(u.spent ? "spent@" + u.spentAtHeight : "UNSPENT").append("  cov").append(u.covenantType).append('\n');
        }

        sb.append('\n').append("Names owned now (name  covenant  coinValue  height  nameHash):\n");
        for (WalletSession.OwnedName o : s.ownedNames()) {
            sb.append(o.name).append("  cov").append(o.covenantType).append(o.transferring ? "(transferring)" : "").append("  ")
                    .append(hns(o.coinValue)).append("  ").append(o.height).append("  ").append(hex(o.nameHash)).append('\n');
        }
        return sb.toString();
    }

    private static String hns(long dollarydoos) {
        return String.format("%d.%06d", dollarydoos / 1_000_000L, dollarydoos % 1_000_000L);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}