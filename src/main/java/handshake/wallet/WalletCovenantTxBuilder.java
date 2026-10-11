package handshake.wallet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds and signs the full name-covenant lifecycle: OPEN, BID, REVEAL,
 * REGISTER, RENEW, TRANSFER, FINALIZE, REVOKE, UPDATE, REDEEM. CLAIM is
 * deliberately not included -- coinbase-only, not something an ordinary
 * wallet transaction can construct.
 *
 * Every covenant item layout here was extracted directly from real
 * hsd's own consensus-level validation (lib/covenants/rules.js's
 * hasSaneCovenants() and verifyCovenants()), not inferred from this
 * project's own read-side code or guessed at -- given the stakes
 * (a wrong BID/REVEAL construction can permanently, unrecoverably
 * forfeit real funds), every item count, field size, and field meaning
 * below is the exact, real consensus requirement, not an approximation.
 *
 * A structural pattern worth stating once, up front: OPEN's covenant
 * value must be 0, but every covenant from BID onward that spends a
 * PRIOR name covenant (REGISTER, UPDATE, RENEW, FINALIZE, REVOKE, and
 * TRANSFER) must preserve the exact same output value as the coin it
 * spends -- "money is now locked up forever" once a name enters this
 * chain, confirmed directly from verifyCovenants(). That means none of
 * these can pay their own transaction fee out of the covenant output
 * itself; every one of them needs a separate, ordinary wallet UTXO
 * added purely to cover the fee, with any leftover returned as change.
 */
public final class WalletCovenantTxBuilder {

    public static class BuildResult {
        public boolean success;
        public String error;
        public byte[] signedTx;
        public byte[] covenantOutputTxid; // this transaction's own txid, for chaining to the next covenant step
    }

    private WalletCovenantTxBuilder() {}

    // ── OPEN ──────────────────────────────────────────────────────────────
    // items: [nameHash(32), height=0(4), name(var)] -- height MUST be
    // zero here specifically (confirmed: hasSaneCovenants() explicitly
    // rejects a nonzero height for OPEN). Value 0, matching real-world
    // convention (an OPEN announces, it doesn't lock funds).
    public static BuildResult buildOpen(WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
                                        String name, long feeRatePerByte) {
        BuildResult result = new BuildResult();
        try {
            byte[] nameHash = UrkelNameHash.hashName(name);
            HDKey outAddrKey = HandshakeKeyPath.deriveAddressKey(accountKey, HandshakeKeyPath.CHANGE_EXTERNAL,
                    addrMgr.highestUsed(HandshakeKeyPath.CHANGE_EXTERNAL) + 1);
            byte[] outAddrHash = Blake2b.hash160(outAddrKey.publicKey());

            TxParser.Output openOutput = covenantOutput(0, 0, outAddrHash, TxParser.COV_OPEN,
                    nameHash, u32(0), name.getBytes(java.nio.charset.StandardCharsets.US_ASCII));

            return feeOnlyTransaction(utxoSet, addrMgr, accountKey, List.of(openOutput), feeRatePerByte);
        } catch (Exception e) {
            result.error = "exception building OPEN: " + e;
            return result;
        }
    }

    // ── BID ───────────────────────────────────────────────────────────────
    // items: [nameHash(32), height(4), name(var), blind(32)]. Output
    // value = lockup (NOT the real bid value -- that's secret until
    // REVEAL). The caller must have already called
    // WalletBidManager.createBid() to get the blind to embed here.
    public static BuildResult buildBid(WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
                                       WalletBidManager.PendingBid bid, long feeRatePerByte) {
        BuildResult result = new BuildResult();
        try {
            TxParser.Output bidOutput = covenantOutput(bid.lockup, 0, bid.bidAddrHash, TxParser.COV_BID,
                    bid.nameHash, u32(bid.openHeight), bid.name.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    bid.blind);

            return feeOnlyTransaction(utxoSet, addrMgr, accountKey, List.of(bidOutput), feeRatePerByte);
        } catch (Exception e) {
            result.error = "exception building BID: " + e;
            return result;
        }
    }

    // ── REVEAL ────────────────────────────────────────────────────────────
    // items: [nameHash(32), height(4), nonce(32)]. Spends the BID output
    // specifically. Output value = the REAL bid value (this is the one
    // place it's ever disclosed) -- and it's this value, together with
    // the nonce, that consensus rules re-derive the blind from and check
    // against the spent BID's own committed blind.
    public static BuildResult buildReveal(WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
                                          WalletBidManager.PendingBid bid, long feeRatePerByte) {
        BuildResult result = new BuildResult();
        try {
            if (bid.bidTxid == null) {
                result.error = "this pending bid has no recorded BID outpoint yet -- call recordBidOutpoint() after broadcasting the BID";
                return result;
            }
            WalletUtxoSet.Utxo bidUtxo = findUtxo(utxoSet, bid.bidTxid, bid.bidOutputIndex);
            if (bidUtxo == null) {
                result.error = "the BID output this reveal needs to spend is not a known UTXO (not yet confirmed, or already spent)";
                return result;
            }

            TxParser.Input bidInput = utxoToInput(bidUtxo);
            TxParser.Output revealOutput = covenantOutput(bid.value, 0, bid.bidAddrHash, TxParser.COV_REVEAL,
                    bid.nameHash, u32(bid.openHeight), bid.nonce);

            List<TxParser.Input> inputs = new ArrayList<>();
            inputs.add(bidInput);
            List<WalletUtxoSet.Utxo> spentUtxos = new ArrayList<>();
            spentUtxos.add(bidUtxo);
            List<TxParser.Output> outputs = new ArrayList<>();
            outputs.add(revealOutput);

            // REVEAL's own output value is fixed (the real bid, being
            // disclosed) but the BID coin being spent (the lockup) is
            // usually larger, often deliberately so -- obscuring the real
            // bid behind a bigger, visible lockup is the normal,
            // recommended way to bid, exactly like the earlier walkthrough
            // of a 350,000 lockup around a 50,000 real bid. The
            // difference is NOT a fee -- it's returned to the bidder as
            // an ordinary change output in this same transaction,
            // confirmed directly from real hsd's own wallet.js
            // (makeRevealAll() builds exactly one REVEAL-covenant output
            // for the disclosed value, then lets its own coin-filling step
            // add a change output for whatever's left over). An earlier
            // version of this method got this wrong -- treating the whole
            // difference as absorbed into the fee, which would have
            // silently destroyed the entire obscuring amount on every
            // single reveal that used one, exactly the case a real bidder
            // is expected to use.
            long available = bidUtxo.value - bid.value;
            long fee = WalletTxBuilder.estimateFee(1, 2, feeRatePerByte); // 2 outputs: reveal + change, in case one's needed
            long feeNoChange = WalletTxBuilder.estimateFee(1, 1, feeRatePerByte);
            byte[] changeAddrHash = null;
            int changeIndex = -1;
            if (available - fee >= 1000) { // above dust threshold -- worth a real change output
                changeIndex = addrMgr.highestUsed(HandshakeKeyPath.CHANGE_INTERNAL) + 1;
                HDKey changeKey = HandshakeKeyPath.deriveAddressKey(accountKey, HandshakeKeyPath.CHANGE_INTERNAL, changeIndex);
                changeAddrHash = Blake2b.hash160(changeKey.publicKey());
                outputs.add(WalletTxBuilder.plainOutput(available - fee, 0, changeAddrHash));
            } else if (available < feeNoChange) {
                // The lockup alone isn't even enough to cover the fee
                // without a change output (a tight or zero-margin bid) --
                // top up with a separate wallet UTXO rather than fail.
                addFeeInputs(utxoSet, addrMgr, accountKey, inputs, spentUtxos, outputs, feeRatePerByte, 1, feeNoChange - available);
            }
            // else: available - feeNoChange is small, positive, but below
            // dust -- genuinely too small to be worth a change output;
            // folded into the fee, the same dust-handling choice
            // WalletTxBuilder already makes for ordinary sends.

            byte[] signedTx = WalletTxBuilder.signTransaction(inputs, outputs, spentUtxos, addrMgr, accountKey);
            if (signedTx == null) { result.error = "no signing key for one of the spent UTXOs"; return result; }
            if (changeIndex != -1) addrMgr.markUsed(HandshakeKeyPath.CHANGE_INTERNAL, changeIndex);
            result.success = true;
            result.signedTx = signedTx;
            result.covenantOutputTxid = Blake2b.hash256(java.util.Arrays.copyOf(signedTx, TxParser.parse(signedTx).baseSize));
            return result;
        } catch (Exception e) {
            result.error = "exception building REVEAL: " + e;
            return result;
        }
    }

    // ── REGISTER ──────────────────────────────────────────────────────────
    // items: [nameHash(32), height(4), resourceData(<=512), blockHash(32)].
    // Spends the WINNING reveal. Output must pay the SAME address as
    // that reveal (confirmed: verifyCovenants() checks
    // output.address.equals(coin.address) for this transition).
    //
    // registerValue is passed in explicitly, not computed -- real hsd
    // uses a Vickrey (second-price) auction, so the correct value here
    // is the SECOND-highest bid revealed for this name, not this
    // wallet's own. A bloom-filter-based SPV wallet watching only its
    // own addresses has no way to see other bidders' REVEAL
    // transactions, so it cannot reliably determine this value on its
    // own -- a real, unresolved gap, not something to approximate.
    // Whatever supplies this value (a full-node query, a future wider
    // covenant-aware filter strategy) is a separate problem from
    // constructing the transaction itself once the right value is known.
    public static BuildResult buildRegister(WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
                                            byte[] nameHash, int openHeight, byte[] revealTxid, int revealOutputIndex,
                                            long registerValue, byte[] resourceData, byte[] recentBlockHash,
                                            long feeRatePerByte) {
        BuildResult result = new BuildResult();
        try {
            WalletUtxoSet.Utxo revealUtxo = findUtxo(utxoSet, revealTxid, revealOutputIndex);
            if (revealUtxo == null) {
                result.error = "the winning REVEAL output is not a known UTXO";
                return result;
            }
            if (registerValue > revealUtxo.value) {
                result.error = "registerValue (" + registerValue + ") cannot exceed the winning reveal's own value ("
                        + revealUtxo.value + ")";
                return result;
            }

            TxParser.Output registerOutput = covenantOutput(registerValue, 0, revealUtxo.addrHash, TxParser.COV_REGISTER,
                    nameHash, u32(openHeight), resourceData != null ? resourceData : new byte[0], recentBlockHash);

            return spendCovenantWithFee(utxoSet, addrMgr, accountKey, revealUtxo, registerOutput, feeRatePerByte);
        } catch (Exception e) {
            result.error = "exception building REGISTER: " + e;
            return result;
        }
    }

    // ── REDEEM ────────────────────────────────────────────────────────────
    // items: [nameHash(32), height(4)]. Spends a LOSING reveal, returning
    // its funds to the bidder (minus fee). No address-matching or
    // value-preservation constraint here (unlike REGISTER onward) --
    // this is the one covenant step that genuinely frees the money.
    public static BuildResult buildRedeem(WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
                                          byte[] nameHash, int openHeight, byte[] losingRevealTxid,
                                          int losingRevealOutputIndex, long feeRatePerByte) {
        BuildResult result = new BuildResult();
        try {
            WalletUtxoSet.Utxo revealUtxo = findUtxo(utxoSet, losingRevealTxid, losingRevealOutputIndex);
            if (revealUtxo == null) { result.error = "the losing REVEAL output is not a known UTXO"; return result; }

            HDKey outAddrKey = HandshakeKeyPath.deriveAddressKey(accountKey, HandshakeKeyPath.CHANGE_INTERNAL,
                    addrMgr.highestUsed(HandshakeKeyPath.CHANGE_INTERNAL) + 1);
            byte[] outAddrHash = Blake2b.hash160(outAddrKey.publicKey());

            long fee = WalletTxBuilder.estimateFee(1, 1, feeRatePerByte);
            if (revealUtxo.value <= fee) { result.error = "losing reveal's value is too small to cover even the fee"; return result; }
            long redeemValue = revealUtxo.value - fee;

            TxParser.Output redeemOutput = covenantOutput(redeemValue, 0, outAddrHash, TxParser.COV_REDEEM,
                    nameHash, u32(openHeight));

            List<TxParser.Input> inputs = new ArrayList<>();
            inputs.add(utxoToInput(revealUtxo));
            List<WalletUtxoSet.Utxo> spentUtxos = new ArrayList<>();
            spentUtxos.add(revealUtxo);

            byte[] signedTx = WalletTxBuilder.signTransaction(inputs, List.of(redeemOutput), spentUtxos, addrMgr, accountKey);
            if (signedTx == null) { result.error = "no signing key for the reveal UTXO"; return result; }
            result.success = true;
            result.signedTx = signedTx;
            return result;
        } catch (Exception e) {
            result.error = "exception building REDEEM: " + e;
            return result;
        }
    }

    // ── UPDATE ────────────────────────────────────────────────────────────
    // items: [nameHash(32), height(4), resourceData(<=512)].
    public static BuildResult buildUpdate(WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
                                          byte[] nameHash, int openHeight, byte[] currentOwnerTxid,
                                          int currentOwnerOutputIndex, byte[] resourceData, long feeRatePerByte) {
        return spendCovenantPreservingValue(utxoSet, addrMgr, accountKey, nameHash, openHeight,
                currentOwnerTxid, currentOwnerOutputIndex, TxParser.COV_UPDATE,
                new byte[][]{resourceData != null ? resourceData : new byte[0]}, feeRatePerByte);
    }

    // ── RENEW ─────────────────────────────────────────────────────────────
    // items: [nameHash(32), height(4), blockHash(32)] -- must commit to a
    // block from within the past ~6 months (per real hsd; not enforced
    // here, the caller supplies a real recent block hash).
    public static BuildResult buildRenew(WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
                                         byte[] nameHash, int openHeight, byte[] currentOwnerTxid,
                                         int currentOwnerOutputIndex, byte[] recentBlockHash, long feeRatePerByte) {
        return spendCovenantPreservingValue(utxoSet, addrMgr, accountKey, nameHash, openHeight,
                currentOwnerTxid, currentOwnerOutputIndex, TxParser.COV_RENEW,
                new byte[][]{recentBlockHash}, feeRatePerByte);
    }

    // ── TRANSFER ──────────────────────────────────────────────────────────
    // items: [nameHash(32), height(4), newAddrVersion(1), newAddrHash(2-40)].
    // The UTXO itself stays at the CURRENT owner's address until
    // FINALIZE -- this covenant only announces the intended recipient.
    public static BuildResult buildTransfer(WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
                                            byte[] nameHash, int openHeight, byte[] currentOwnerTxid,
                                            int currentOwnerOutputIndex, int newAddrVersion, byte[] newAddrHash,
                                            long feeRatePerByte) {
        return spendCovenantPreservingValue(utxoSet, addrMgr, accountKey, nameHash, openHeight,
                currentOwnerTxid, currentOwnerOutputIndex, TxParser.COV_TRANSFER,
                new byte[][]{new byte[]{(byte) newAddrVersion}, newAddrHash}, feeRatePerByte);
    }

    // ── FINALIZE ──────────────────────────────────────────────────────────
    // items: [nameHash(32), height(4), name(var), flags(1), claimHeight(4),
    // renewalCount(4), blockHash(32)] -- 7 items, the largest of any
    // covenant. Unlike UPDATE/RENEW/REVOKE, this one DOES move the UTXO:
    // its output must pay the address committed to in the ORIGINAL
    // TRANSFER covenant (confirmed directly from verifyCovenants()'s
    // FINALIZE case), not the current owner's own address.
    public static BuildResult buildFinalize(WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
                                            byte[] nameHash, int openHeight, String name,
                                            byte[] transferTxid, int transferOutputIndex,
                                            int newAddrVersion, byte[] newAddrHash,
                                            int claimHeight, int renewalCount, byte[] recentBlockHash,
                                            long feeRatePerByte) {
        BuildResult result = new BuildResult();
        try {
            WalletUtxoSet.Utxo transferUtxo = findUtxo(utxoSet, transferTxid, transferOutputIndex);
            if (transferUtxo == null) { result.error = "the TRANSFER output is not a known UTXO"; return result; }

            TxParser.Output finalizeOutput = covenantOutput(transferUtxo.value, newAddrVersion, newAddrHash,
                    TxParser.COV_FINALIZE, nameHash, u32(openHeight),
                    name.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    new byte[]{0}, u32(claimHeight), u32(renewalCount), recentBlockHash);

            return spendCovenantWithFee(utxoSet, addrMgr, accountKey, transferUtxo, finalizeOutput, feeRatePerByte);
        } catch (Exception e) {
            result.error = "exception building FINALIZE: " + e;
            return result;
        }
    }

    // ── REVOKE ────────────────────────────────────────────────────────────
    // items: [nameHash(32), height(4)]. Permanently burns the name --
    // "Revocations are perma-burned" (verifyCovenants() rejects any
    // further spend of a REVOKE output whatsoever).
    public static BuildResult buildRevoke(WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
                                          byte[] nameHash, int openHeight, byte[] currentOwnerTxid,
                                          int currentOwnerOutputIndex, long feeRatePerByte) {
        return spendCovenantPreservingValue(utxoSet, addrMgr, accountKey, nameHash, openHeight,
                currentOwnerTxid, currentOwnerOutputIndex, TxParser.COV_REVOKE, new byte[][]{}, feeRatePerByte);
    }

    // ── Shared helpers ────────────────────────────────────────────────────

    /** UPDATE/RENEW/TRANSFER/REVOKE all share the same shape: spend the
     *  current owner's covenant UTXO, preserve its exact value and
     *  address in the new output, append the given extra covenant items
     *  after [nameHash, height], and fund the fee from a separate
     *  input/change. */
    private static BuildResult spendCovenantPreservingValue(
            WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
            byte[] nameHash, int openHeight, byte[] ownerTxid, int ownerOutputIndex,
            int covenantType, byte[][] extraItems, long feeRatePerByte) {
        BuildResult result = new BuildResult();
        try {
            WalletUtxoSet.Utxo ownerUtxo = findUtxo(utxoSet, ownerTxid, ownerOutputIndex);
            if (ownerUtxo == null) { result.error = "the current owner UTXO is not known to this wallet"; return result; }

            List<Object> items = new ArrayList<>();
            items.add(nameHash);
            items.add(u32(openHeight));
            for (byte[] extra : extraItems) items.add(extra);
            byte[][] itemArray = items.toArray(new byte[0][]);

            TxParser.Output out = covenantOutput(ownerUtxo.value, 0, ownerUtxo.addrHash, covenantType, itemArray);
            return spendCovenantWithFee(utxoSet, addrMgr, accountKey, ownerUtxo, out, feeRatePerByte);
        } catch (Exception e) {
            result.error = "exception building covenant transaction: " + e;
            return result;
        }
    }

    /** Spends exactly one covenant-carrying UTXO (its value fully
     *  preserved in the new output) plus whatever additional ordinary
     *  UTXOs are needed to cover the fee, with change if any is left
     *  over -- the shared shape behind REGISTER, FINALIZE, and every
     *  value-preserving covenant step. */
    private static BuildResult spendCovenantWithFee(WalletUtxoSet utxoSet, WalletAddressManager addrMgr,
                                                    HDKey accountKey, WalletUtxoSet.Utxo covenantUtxo,
                                                    TxParser.Output covenantOutput, long feeRatePerByte) {
        BuildResult result = new BuildResult();
        List<TxParser.Input> inputs = new ArrayList<>();
        inputs.add(utxoToInput(covenantUtxo));
        List<WalletUtxoSet.Utxo> spentUtxos = new ArrayList<>();
        spentUtxos.add(covenantUtxo);
        List<TxParser.Output> outputs = new ArrayList<>();
        outputs.add(covenantOutput);

        long fee = WalletTxBuilder.estimateFee(1, 1, feeRatePerByte);
        addFeeInputs(utxoSet, addrMgr, accountKey, inputs, spentUtxos, outputs, feeRatePerByte, 1, fee);

        byte[] signedTx = WalletTxBuilder.signTransaction(inputs, outputs, spentUtxos, addrMgr, accountKey);
        if (signedTx == null) { result.error = "no signing key for one of the spent UTXOs"; return result; }
        result.success = true;
        result.signedTx = signedTx;
        try {
            result.covenantOutputTxid = Blake2b.hash256(java.util.Arrays.copyOf(signedTx, TxParser.parse(signedTx).baseSize));
        } catch (Exception ignored) {}
        return result;
    }

    /** A transaction with no pre-existing covenant UTXO to spend --
     *  OPEN and BID both need ordinary wallet funds for their covenant
     *  output (BID's value is the lockup; OPEN's is zero but the
     *  transaction still needs an input to pay its fee), selected and
     *  signed the same way WalletTxBuilder already does for a plain
     *  send. */
    private static BuildResult feeOnlyTransaction(WalletUtxoSet utxoSet, WalletAddressManager addrMgr,
                                                  HDKey accountKey, List<TxParser.Output> covenantOutputs,
                                                  long feeRatePerByte) {
        BuildResult result = new BuildResult();
        long covenantOutputsValue = 0;
        for (TxParser.Output o : covenantOutputs) covenantOutputsValue += o.value;

        List<WalletUtxoSet.Utxo> available = new ArrayList<>(utxoSet.getSpendableUtxos());
        available.sort(Comparator.comparingLong((WalletUtxoSet.Utxo u) -> u.value).reversed());

        List<TxParser.Input> inputs = new ArrayList<>();
        List<WalletUtxoSet.Utxo> selected = new ArrayList<>();
        long selectedTotal = 0;
        long fee = 0;
        for (WalletUtxoSet.Utxo utxo : available) {
            inputs.add(utxoToInput(utxo));
            selected.add(utxo);
            selectedTotal += utxo.value;
            fee = WalletTxBuilder.estimateFee(selected.size(), covenantOutputs.size() + 1, feeRatePerByte);
            if (selectedTotal >= covenantOutputsValue + fee) break;
        }
        if (selectedTotal < covenantOutputsValue + fee) {
            result.error = "insufficient funds: have " + selectedTotal + ", need " + (covenantOutputsValue + fee);
            return result;
        }

        List<TxParser.Output> outputs = new ArrayList<>(covenantOutputs);
        long changeAmount = selectedTotal - covenantOutputsValue - fee;
        if (changeAmount > 0) {
            int changeIndex = addrMgr.highestUsed(HandshakeKeyPath.CHANGE_INTERNAL) + 1;
            HDKey changeKey = HandshakeKeyPath.deriveAddressKey(accountKey, HandshakeKeyPath.CHANGE_INTERNAL, changeIndex);
            byte[] changeAddrHash = Blake2b.hash160(changeKey.publicKey());
            outputs.add(WalletTxBuilder.plainOutput(changeAmount, 0, changeAddrHash));
            addrMgr.markUsed(HandshakeKeyPath.CHANGE_INTERNAL, changeIndex);
        }

        byte[] signedTx = WalletTxBuilder.signTransaction(inputs, outputs, selected, addrMgr, accountKey);
        if (signedTx == null) { result.error = "no signing key for one of the selected UTXOs"; return result; }
        result.success = true;
        result.signedTx = signedTx;
        try {
            result.covenantOutputTxid = Blake2b.hash256(java.util.Arrays.copyOf(signedTx, TxParser.parse(signedTx).baseSize));
        } catch (Exception ignored) {}
        return result;
    }

    /** Adds enough additional ordinary UTXOs to the given in-progress
     *  input/output lists to cover a specific remaining fee shortfall,
     *  with change appended if any is left over. */
    private static void addFeeInputs(WalletUtxoSet utxoSet, WalletAddressManager addrMgr, HDKey accountKey,
                                     List<TxParser.Input> inputs, List<WalletUtxoSet.Utxo> spentUtxos,
                                     List<TxParser.Output> outputs, long feeRatePerByte,
                                     int existingInputCount, long shortfall) {
        List<WalletUtxoSet.Utxo> available = new ArrayList<>(utxoSet.getSpendableUtxos());
        available.removeAll(spentUtxos);
        available.sort(Comparator.comparingLong((WalletUtxoSet.Utxo u) -> u.value).reversed());

        long added = 0;
        int addedCount = 0;
        for (WalletUtxoSet.Utxo utxo : available) {
            inputs.add(utxoToInput(utxo));
            spentUtxos.add(utxo);
            added += utxo.value;
            addedCount++;
            long totalFee = WalletTxBuilder.estimateFee(existingInputCount + addedCount, outputs.size() + 1, feeRatePerByte);
            if (added >= shortfall + (totalFee - WalletTxBuilder.estimateFee(existingInputCount, outputs.size(), feeRatePerByte))) break;
        }

        long finalFee = WalletTxBuilder.estimateFee(inputs.size(), outputs.size() + 1, feeRatePerByte);
        long change = added - shortfall - (finalFee - WalletTxBuilder.estimateFee(existingInputCount, outputs.size(), feeRatePerByte));
        if (change > 0) {
            int changeIndex = addrMgr.highestUsed(HandshakeKeyPath.CHANGE_INTERNAL) + 1;
            HDKey changeKey = HandshakeKeyPath.deriveAddressKey(accountKey, HandshakeKeyPath.CHANGE_INTERNAL, changeIndex);
            byte[] changeAddrHash = Blake2b.hash160(changeKey.publicKey());
            outputs.add(WalletTxBuilder.plainOutput(change, 0, changeAddrHash));
            addrMgr.markUsed(HandshakeKeyPath.CHANGE_INTERNAL, changeIndex);
        }
    }

    private static WalletUtxoSet.Utxo findUtxo(WalletUtxoSet utxoSet, byte[] txid, int outputIndex) {
        for (WalletUtxoSet.Utxo u : utxoSet.getUnspentUtxos()) {
            if (java.util.Arrays.equals(u.txid, txid) && u.outputIndex == outputIndex) return u;
        }
        return null;
    }

    private static TxParser.Input utxoToInput(WalletUtxoSet.Utxo utxo) {
        TxParser.Input in = new TxParser.Input();
        in.prevHash = utxo.txid;
        in.prevTxid = reverseHex(utxo.txid);
        in.prevIndex = utxo.outputIndex;
        in.sequence = 0xFFFFFFFF;
        return in;
    }

    private static TxParser.Output covenantOutput(long value, int addrVersion, byte[] addrHash, int covenantType,
                                                  byte[]... items) {
        TxParser.Output out = new TxParser.Output();
        out.value = value;
        out.addrVersion = addrVersion;
        out.addrHash = addrHash;
        out.covenant = new TxParser.Covenant();
        out.covenant.type = covenantType;
        for (byte[] item : items) {
            TxParser.CovenantItem ci = new TxParser.CovenantItem();
            ci.data = item;
            out.covenant.items.add(ci);
        }
        return out;
    }

    private static byte[] u32(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    private static String reverseHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = b.length - 1; i >= 0; i--) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }
}