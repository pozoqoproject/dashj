/*
 * Copyright (c) 2022 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bitcoinj.coinjoin;

import org.bitcoinj.coinjoin.utils.CompactTallyItem;
import org.bitcoinj.coinjoin.utils.KeyHolderStorage;
import org.bitcoinj.coinjoin.utils.TransactionBuilder;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.bitcoinj.evolution.Masternode;
import org.bitcoinj.utils.Pair;
import org.bitcoinj.wallet.Balance;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.bitcoinj.coinjoin.CoinJoinConstants.COINJOIN_DENOM_OUTPUTS_THRESHOLD;
import static org.bitcoinj.coinjoin.PoolMessage.MSG_POOL_MAX;
import static org.bitcoinj.coinjoin.PoolMessage.MSG_POOL_MIN;
import static org.bitcoinj.coinjoin.PoolState.POOL_STATE_ERROR;
import static org.bitcoinj.coinjoin.PoolState.POOL_STATE_IDLE;
import static org.bitcoinj.coinjoin.PoolState.POOL_STATE_MAX;
import static org.bitcoinj.coinjoin.PoolState.POOL_STATE_MIN;
import static org.bitcoinj.coinjoin.PoolState.POOL_STATE_QUEUE;

public class CoinJoinClientSession extends CoinJoinBaseSession {
    private static final Logger log = LoggerFactory.getLogger(CoinJoinClientSession.class);
    private Context context;
    private ArrayList<TransactionOutPoint> outPointLocked;

    private String strLastMessage;
    private String strAutoDenomResult;

    private Masternode mixingMasternode;
    private Transaction txMyCollateral; // client side collateral
    private PendingDsaRequest pendingDsaRequest;

    private KeyHolderStorage keyHolderStorage; // storage for keys used in PrepareDenominate

    private Wallet mixingWallet;

    public CoinJoinClientSession(Context context) {
        this.context = context;
    }

    /// Create denominations
    private boolean createDenominated(Coin balanceToDenominate) {

        if (!CoinJoinClientOptions.isEnabled()) return false;

        // NOTE: We do not allow txes larger than 100 kB, so we have to limit number of inputs here.
        // We still want to consume a lot of inputs to avoid creating only smaller denoms though.
        // Knowing that each CTxIn is at least 148 B big, 400 inputs should take 400 x ~148 B = ~60 kB.
        // This still leaves more than enough room for another data of typical CreateDenominated tx.
        List<CompactTallyItem> vecTally = mixingWallet.selectCoinsGroupedByAddresses(true, true, true, 400);
        if (vecTally.isEmpty()) {
            log.info( "coinjoin: selectCoinsGroupedByAddresses can't find any inputs!");
            return false;
        }

        // Start from the largest balances first to speed things up by creating txes with larger/largest denoms included
        vecTally.sort(new Comparator<CompactTallyItem>() {
            @Override
            public int compare(CompactTallyItem o, CompactTallyItem t1) {
                if (o.amount.isGreaterThan(t1.amount))
                    return 1;
                if (o.amount.equals(t1.amount))
                    return 0;
                return -1;
            }
        });

        boolean fCreateMixingCollaterals = !mixingWallet.hasCollateralInputs();

        for (CompactTallyItem item : vecTally) {
            if (!createDenominated(balanceToDenominate, item, fCreateMixingCollaterals)) continue;
            return true;
        }

        log.info( "coinjoin: createDenominated -- failed!");
        return false;
    }

    static class Result<T> {
        private T result;

        public Result(T result) {
            this.result = result;
        }

        public T get() {
            return result;
        }

        public void set(T result) {
            this.result = result;
        }
    }

    interface NeedMoreOutputs {
        boolean process(Coin balanceToDenominate, int outputs, Result<Boolean> addFinal);
    }

    interface CountPossibleOutputs {
        int process(Coin amount);
    }

    private boolean createDenominated(Coin balanceToDenominate, CompactTallyItem tallyItem, boolean fCreateMixingCollaterals) {

        if (!CoinJoinClientOptions.isEnabled()) 
            return false;

        // denominated input is always a single one, so we can check its amount directly and return early
        if (tallyItem.inputCoins.size() == 1 && CoinJoin.isDenominatedAmount(tallyItem.amount)) {
            return false;
        }

        Wallet wallet = mixingWallet;

        TransactionBuilder txBuilder = new TransactionBuilder(wallet, tallyItem);

        log.info("coinjoin: Start {}", txBuilder);

        // ****** Add an output for mixing collaterals ************ /

        if (fCreateMixingCollaterals && txBuilder.addOutput(CoinJoin.getMaxCollateralAmount()) == null) {
            log.info("coinjoin: Failed to add collateral output");
            return false;
        }

        // ****** Add outputs for denoms ************ /

        final Result<Boolean> addFinal = new Result(true);
        List<Coin> denoms = CoinJoin.getStandardDenominations();

        HashMap<Coin, Integer> mapDenomCount = new HashMap<>();
        for (Coin denomValue : denoms) {
            mapDenomCount.put(denomValue, mixingWallet.countInputsWithAmount(denomValue));
        }

        // Will generate outputs for the createdenoms up to coinjoinmaxdenoms per denom

        // This works in the way creating PS denoms has traditionally worked, assuming enough funds,
        // it will start with the smallest denom then create 11 of those, then go up to the next biggest denom create 11
        // and repeat. Previously, once the largest denom was reached, as many would be created were created as possible and
        // then any remaining was put into a change address and denominations were created in the same manner a block later.
        // Now, in this system, so long as we don't reach COINJOIN_DENOM_OUTPUTS_THRESHOLD outputs the process repeats in
        // the same transaction, creating up to nCoinJoinDenomsHardCap per denomination in a single transaction.

        while (txBuilder.couldAddOutput(CoinJoin.getSmallestDenomination()) && txBuilder.countOutputs() < COINJOIN_DENOM_OUTPUTS_THRESHOLD) {
            for (int it = denoms.size() - 1; it >= 0; --it) {
                Coin denomValue = denoms.get(it);
                Integer currentDenomIt = mapDenomCount.get(denomValue);

                int nOutputs = 0;


                NeedMoreOutputs needMoreOutputs = new NeedMoreOutputs() {
                    @Override
                    public boolean process(Coin balanceToDenominate, int outputs, Result<Boolean> addFinal) {
                        if (txBuilder.couldAddOutput(denomValue)) {
                            if (addFinal.get() && balanceToDenominate.isPositive() && balanceToDenominate.isLessThan(denomValue)) {
                                addFinal.set(false); // add final denom only once, only the smalest possible one
                                log.info("coinjoin: 1 - FINAL - denomValue: {}, balanceToDenominate: {}, outputs: {}, {}",
                                        denomValue.toFriendlyString(), balanceToDenominate.toFriendlyString(), outputs, txBuilder);
                                return true;
                            } else if (balanceToDenominate.isGreaterThanOrEqualTo(denomValue)) {
                                return true;
                            }
                        }
                        return false;
                    }
                };

                // add each output up to 11 times or until it can't be added again or until we reach nCoinJoinDenomsGoal
                while (needMoreOutputs.process(balanceToDenominate, nOutputs, addFinal) && nOutputs <= 10 && currentDenomIt < CoinJoinClientOptions.getDenomsGoal()) {
                    // Add output and subtract denomination amount
                    if (txBuilder.addOutput(denomValue) != null) {
                        ++nOutputs;
                        ++currentDenomIt;
                        balanceToDenominate = balanceToDenominate.subtract(denomValue);
                        log.info("coinjoin: 1 - denomValue: {}, balanceToDenominate: {}, nOutputs: {}, {}",
                                denomValue.toFriendlyString(), balanceToDenominate.toFriendlyString(), nOutputs, txBuilder);
                    } else {
                        log.info("coinjoin: 1 - Error: AddOutput failed for denomValue: {}, balanceToDenominate: {}, nOutputs: {}, {}",
                                denomValue.toFriendlyString(), balanceToDenominate.toFriendlyString(), nOutputs, txBuilder);
                        return false;
                    }

                }

                if (txBuilder.getAmountLeft().isZero() || balanceToDenominate.isLessThanOrEqualTo(Coin.ZERO)) break;
            }

            boolean finished = true;
            for (Map.Entry<Coin, Integer> entry : mapDenomCount.entrySet()) {
                // Check if this specific denom could use another loop, check that there aren't nCoinJoinDenomsGoal of this
                // denom and that our nValueLeft/balanceToDenominate is enough to create one of these denoms, if so, loop again.
                if (entry.getValue() < CoinJoinClientOptions.getDenomsGoal() && txBuilder.couldAddOutput(entry.getKey()) && balanceToDenominate.isGreaterThan(Coin.ZERO)) {
                    finished = false;
                    log.info("coinjoin: 1 - NOT finished - denomValue: {}, count: {}, balanceToDenominate: {}, {}",
                            entry.getKey().toFriendlyString(), entry.getValue(), balanceToDenominate.toFriendlyString(), txBuilder);
                    break;
                }
                log.info("coinjoin: 1 - FINISHED - denomValue: {}, count: {}, balanceToDenominate: {}, {}",
                        entry.getKey().toFriendlyString(), entry.getValue(), balanceToDenominate.toFriendlyString(), txBuilder);
            }

            if (finished) break;
        }

        // Now that nCoinJoinDenomsGoal worth of each denom have been created or the max number of denoms given the value of the input, do something with the remainder.
        if (txBuilder.couldAddOutput(CoinJoin.getSmallestDenomination()) && balanceToDenominate.isGreaterThanOrEqualTo(CoinJoin.getSmallestDenomination()) && txBuilder.countOutputs() < COINJOIN_DENOM_OUTPUTS_THRESHOLD) {
            Coin largestDenomValue = denoms.get(0);

            log.info("coinjoin: 2 - Process remainder: {}", txBuilder);

            CountPossibleOutputs countPossibleOutputs = new CountPossibleOutputs() {
                @Override
                public int process(Coin amount) {
                    ArrayList<Coin> vecOutputs = new ArrayList<>();
                    while (true) {
                        // Create a potential output
                        vecOutputs.add(amount);
                        if (!txBuilder.couldAddOutputs(vecOutputs) || txBuilder.countOutputs() + vecOutputs.size() > COINJOIN_DENOM_OUTPUTS_THRESHOLD) {
                            // If it's not possible to add it due to insufficient amount left or total number of outputs exceeds
                            // COINJOIN_DENOM_OUTPUTS_THRESHOLD drop the output again and stop trying.
                            vecOutputs.remove(vecOutputs.size() -1);
                            break;
                        }
                    }
                    return vecOutputs.size();
                }
            };

            // Go big to small
            for (Coin denomValue : denoms) {
                if (balanceToDenominate.isGreaterThanOrEqualTo(Coin.ZERO)) break;
                int nOutputs = 0;

                // Number of denoms we can create given our denom and the amount of funds we have left
                int denomsToCreateValue = countPossibleOutputs.process(denomValue);
                // Prefer overshooting the target balance by larger denoms (hence `+1`) instead of a more
                // accurate approximation by many smaller denoms. This is ok because when we get here we
                // should have nCoinJoinDenomsGoal of each smaller denom already. Also, without `+1`
                // we can end up in a situation when there is already nCoinJoinDenomsHardCap of smaller
                // denoms, yet we can't mix the remaining balanceToDenominate because it's smaller than
                // denomValue (and thus denomsToCreateBal == 0), so the target would never get reached
                // even when there is enough funds for that.
                int denomsToCreateBal = (int)(balanceToDenominate.value / denomValue.value + 1);
                // Use the smaller value
                int denomsToCreate = Math.min(denomsToCreateValue, denomsToCreateBal);
                log.info("coinjoin: 2 - balanceToDenominate: {}, denomValue: {}, denomsToCreateValue: {}, denomsToCreateBal: {}",
                        balanceToDenominate.toFriendlyString(), denomValue.toFriendlyString(), denomsToCreateValue, denomsToCreateBal);
                Integer it = mapDenomCount.get(denomValue);
                for (int i = 0; i < denomsToCreate; ++i) {
                    // Never go above the cap unless it's the largest denom
                    if (denomValue != largestDenomValue && it >= CoinJoinClientOptions.getDenomsHardCap()) break;

                    // Increment helpers, add output and subtract denomination amount
                    if (txBuilder.addOutput(denomValue) != null) {
                        nOutputs++;
                        mapDenomCount.put(denomValue, ++it);
                        balanceToDenominate = balanceToDenominate.subtract(denomValue);
                    } else {
                        log.info("coinjoin: 2 - Error: AddOutput failed at {}/{}, {}", i + 1, denomsToCreate, txBuilder);
                        break;
                    }
                    log.info("coinjoin: 2 - denomValue: {}, balanceToDenominate: {}, nOutputs: {}, {}",
                            denomValue.toFriendlyString(), balanceToDenominate.toFriendlyString(), nOutputs, txBuilder);
                    if (txBuilder.countOutputs() >= COINJOIN_DENOM_OUTPUTS_THRESHOLD) break;
                }
                if (txBuilder.countOutputs() >= COINJOIN_DENOM_OUTPUTS_THRESHOLD) break;
            }
        }

        log.info("coinjoin: 3 - balanceToDenominate: {}, {}", balanceToDenominate.toFriendlyString(), txBuilder);

        for (Map.Entry<Coin, Integer> it : mapDenomCount.entrySet()) {
            log.info("coinjoin: 3 - DONE - denomValue: {}, count: {}", it.getKey().toFriendlyString(), it.getValue());
        }

        // No reasons to create mixing collaterals if we can't create denoms to mix
        if ((fCreateMixingCollaterals && txBuilder.countOutputs() == 1) || txBuilder.countOutputs() == 0) {
            return false;
        }

        StringBuilder strResult = new StringBuilder();
        if (!txBuilder.commit(strResult)) {
            log.info("coinjoin: Commit failed: {}", strResult.toString());
            return false;
        }

        // use the same nCachedLastSuccessBlock as for DS mixing to prevent race
        context.coinJoinClientManagers.get(mixingWallet.getDescription()).updatedSuccessBlock();

        log.info("coinjoin: txid: {}", strResult);

        return true;
    }

    /// Split up large inputs or make fee sized inputs
    private boolean makeCollateralAmounts() {

        return false;
    }
    private boolean makeCollateralAmounts(CompactTallyItem tallyItem, boolean fTryDenominated) {

        return fTryDenominated;
    }

    private boolean createCollateralTransaction(Transaction txCollateral, StringBuilder strReason){

        return false;
    }

    private boolean joinExistingQueue(Coin balanceNeedsAnonymized) {
        return false;
    }

    private boolean startNewQueue(Coin balanceNeedsAnonymized) {
        return false;
    }

    /// step 0: select denominated inputs and txouts
    private boolean selectDenominate(StringBuilder strErrorRet, List<CoinJoinTransactionInput> vecTxDSInRet) {

        return false;
    }
    /// step 1: prepare denominated inputs and outputs
    private boolean prepareDenominate(int minRounds, int maxRounds, StringBuilder strErrorRet, List<CoinJoinTransactionInput> vecTxDSIn, List<Pair<CoinJoinTransactionInput, TransactionOutput>> vecPSInOutPairsRet) {
        return prepareDenominate(minRounds, maxRounds, strErrorRet, vecTxDSIn, vecPSInOutPairsRet, false);
    }
    private boolean prepareDenominate(int minRounds, int maxRounds, StringBuilder strErrorRet, List<CoinJoinTransactionInput> vecTxDSIn, List<Pair<CoinJoinTransactionInput, TransactionOutput>> vecPSInOutPairsRet, boolean fDryRun) {

        return fDryRun;
    }
    /// step 2: send denominated inputs and outputs prepared in step 1
    private boolean sendDenominate(List<Pair<CoinJoinTransactionInput, TransactionOutput>> vecPSInOutPairs) {

        return false;
    }

    /// Process Masternode updates about the progress of mixing
    private void processPoolStateUpdate(CoinJoinStatusUpdate statusUpdate) {
        // do not update state when mixing client state is one of these
        if (state.get() == POOL_STATE_IDLE || state.get() == POOL_STATE_ERROR) return;

        if (statusUpdate.getState().value < POOL_STATE_MIN.value || statusUpdate.getState().value > POOL_STATE_MAX.value) {
            log.info("coinjoin session: statusUpdate.state is out of bounds: {}", statusUpdate.getState());
            return;
        }

        if (statusUpdate.getMessageID().value < MSG_POOL_MIN.value || statusUpdate.getMessageID().value > MSG_POOL_MAX.value) {
            log.info("coinjoin session: statusUpdate.nMessageID is out of bounds: {}", statusUpdate.getMessageID());
            return;
        }

        String strMessageTmp = CoinJoin.getMessageByID(statusUpdate.getMessageID());
        strAutoDenomResult = "Masternode:" + " " + strMessageTmp;

        switch (statusUpdate.getStatusUpdate()) {
            case STATUS_REJECTED: {
                log.info("coinjoin session: rejected by Masternode: {}", strMessageTmp);
                setState(POOL_STATE_ERROR);
                unlockCoins();
                keyHolderStorage.returnAll();
                timeLastSuccessfulStep.set(Utils.currentTimeSeconds());
                strLastMessage = strMessageTmp;
                break;
            }
            case STATUS_ACCEPTED: {
                if (state.get() == statusUpdate.getState() && statusUpdate.getState() == POOL_STATE_QUEUE && sessionID.get() == 0 && statusUpdate.getSessionID() != 0) {
                    // new session id should be set only in POOL_STATE_QUEUE state
                    sessionID.set(statusUpdate.getSessionID());
                    timeLastSuccessfulStep.set(Utils.currentTimeSeconds());
                    strMessageTmp = strMessageTmp + " Set nSessionID to " + sessionID.get();
                }
                log.info("coinjoin session: accepted by Masternode: {}", strMessageTmp);
                break;
            }
            default: {
                log.info("coinjoin session: statusUpdate.nStatusUpdate is out of bounds: {}", statusUpdate.getStatusUpdate());
                break;
            }
        }
    }
    // Set the 'state' value, with some logging and capturing when the state changed
    private void setState(PoolState state) {
        this.state.set(state);
    }

    private void completedTransaction(PoolMessage messageID) {

    }

    /// As a client, check and sign the final transaction
    private boolean signFinalTransaction(Transaction finalTransactionNew, Peer node) {

        return false;
    }

    @GuardedBy("lock")
    private void SetNull() {
        // Client side
        mixingMasternode = null;
        pendingDsaRequest = new PendingDsaRequest();

        super.setNull();
    }

    public CoinJoinClientSession(Wallet mixingWallet) {
        this.mixingWallet = mixingWallet;
    }

    public void processStatusUpdate(Peer peer, CoinJoinStatusUpdate statusUpdate) {
        if (mixingMasternode == null)
            return;
        if (!mixingMasternode.getService().getAddr().equals(peer.getAddress().getAddr()))
            return;

        processPoolStateUpdate(statusUpdate);
    }

    public void processFinalTransaction(Peer peer, CoinJoinFinalTransaction finalTransaction) {
        if (mixingMasternode == null)
            return;
        if (!mixingMasternode.getService().getAddr().equals(peer.getAddress().getAddr()))
            return;

        if (sessionID.get() != finalTransaction.getMsgSessionID()) {
            log.info("DSFINALTX -- message doesn't match current CoinJoin session: sessionID: {}  msgSessionID: {}",
                    sessionID, finalTransaction.getMsgSessionID());
            return;
        }

        log.info("DSFINALTX -- txNew {}", finalTransaction.getTransaction()); /* Continued */

        // check to see if input is spent already? (and probably not confirmed)
        signFinalTransaction(finalTransaction.getTransaction(), peer);
    }

    public void processComplete(Peer peer, CoinJoinComplete completeMessage) {
        if (mixingMasternode == null)
            return;
        if (!mixingMasternode.getService().getAddr().equals(peer.getAddress().getAddr()))
            return;

        if (completeMessage.getMsgMessageID().value < MSG_POOL_MIN.value || completeMessage.getMsgMessageID().value > MSG_POOL_MAX.value) {
            log.info("DSCOMPLETE -- nMsgMessageID is out of bounds: {}", completeMessage.getMsgMessageID());
            return;
        }

        if (sessionID.get() != completeMessage.getMsgSessionID()) {
            log.info("DSCOMPLETE -- message doesn't match current CoinJoin session: nSessionID: {}  nMsgSessionID: {}", sessionID.get(), completeMessage.getMsgSessionID());
            return;
        }

        log.info("DSCOMPLETE -- nMsgSessionID {}  nMsgMessageID {} ({})", completeMessage.getMsgSessionID(),
                completeMessage.getMsgMessageID(), CoinJoin.getMessageByID(completeMessage.getMsgMessageID()));

        completedTransaction(completeMessage.getMsgMessageID());
    }

    public void unlockCoins() {
        if (!CoinJoinClientOptions.isEnabled())
            return;

        while (true) {
            //TRY_LOCK(mixingWallet.cs_wallet, lockWallet);
            //if (!lockWallet) {
            //    UninterruptibleSleep(std::chrono::milliseconds{50});
            //    continue;
            //}
            for (TransactionOutPoint outpoint : outPointLocked)
                mixingWallet.unlockCoin(outpoint);
            break;
        }

        outPointLocked.clear();
    }

    public void ResetPool() {
        txMyCollateral = new Transaction(mixingWallet.getParams());
        unlockCoins();
        keyHolderStorage.returnAll();
        SetNull();
    }

    private static int nStatusMessageProgress = 0;

    public String getStatus(boolean fWaitForBlock) {
        nStatusMessageProgress += 10;
        String strSuffix;

        if (fWaitForBlock || !context.masternodeSync.isBlockchainSynced()) {
            return strAutoDenomResult;
        }

        switch (state.get()) {
            case POOL_STATE_IDLE:
                return "CoinJoin is idle.";
            case POOL_STATE_QUEUE:
                if (nStatusMessageProgress % 70 <= 30)
                    strSuffix = ".";
                else if (nStatusMessageProgress % 70 <= 50)
                    strSuffix = "..";
                else
                    strSuffix = "...";
                return String.format("Submitted to masternode, waiting in queue %s", strSuffix);
            case POOL_STATE_ACCEPTING_ENTRIES:
                return strAutoDenomResult;
            case POOL_STATE_SIGNING:
                if (nStatusMessageProgress % 70 <= 40)
                    return "Found enough users, signing ...";
                else if (nStatusMessageProgress % 70 <= 50)
                    strSuffix = ".";
                else if (nStatusMessageProgress % 70 <= 60)
                    strSuffix = "..";
                else
                    strSuffix = "...";
                return String.format("Found enough users, signing ( waiting %s )", strSuffix);
            case POOL_STATE_ERROR:
                return "CoinJoin request incomplete: " + strLastMessage + " Will retry...";
            default:
                return String.format("Unknown state: id = %s", state.get());
        }
    }

    public Masternode getMixingMasternodeInfo() {

        return null;
    }

    /// Passively run mixing in the background according to the configuration in settings
    public boolean doAutomaticDenominating() {
        return doAutomaticDenominating(false);
    }
    public boolean doAutomaticDenominating(boolean fDryRun) {
        if (state.get() != POOL_STATE_IDLE) return false;

        if (!context.masternodeSync.isBlockchainSynced()) {
            strAutoDenomResult = "Can't mix while sync in progress.";
            return false;
        }

        if (!CoinJoinClientOptions.isEnabled()) return false;

        Coin nBalanceNeedsAnonymized = Coin.ZERO;
        {
            if (!fDryRun && mixingWallet.isEncrypted()) {
                strAutoDenomResult = "Wallet is locked.";
                return false;
            }

            if (getEntriesCount() > 0) {
                strAutoDenomResult = "Mixing in progress...";
                return false;
            }

            boolean hasLock = lock.tryLock();
            try {
                if (!hasLock) {
                    strAutoDenomResult = "Lock is already in place.";
                    return false;
                }

                if (context.masternodeListManager.getListAtChainTip().getValidMNsCount() == 0 &&
                        context.getParams().getId() != NetworkParameters.ID_REGTEST) {
                    strAutoDenomResult = "No Masternodes detected.";
                    log.info("coinjoin: {}", strAutoDenomResult);
                    return false;
                }

                Balance bal = mixingWallet.getBalanceInfo();

                // check if there is anything left to do
                Coin nBalanceAnonymized = bal.getAnonymized();
                nBalanceNeedsAnonymized = CoinJoinClientOptions.getAmount().subtract(nBalanceAnonymized);

                if (nBalanceNeedsAnonymized.isLessThanOrEqualTo(Coin.ZERO)) {
                    log.info("coinjoin: Nothing to do\n");
                    // nothing to do, just keep it in idle mode
                    return false;
                }

                Coin nValueMin = CoinJoin.getSmallestDenomination();

                // if there are no confirmed DS collateral inputs yet
                if (!mixingWallet.hasCollateralInputs()) {
                    // should have some additional amount for them
                    nValueMin = nValueMin.add(CoinJoin.getMaxCollateralAmount());
                }

                // including denoms but applying some restrictions
                Coin nBalanceAnonymizable = mixingWallet.getAnonymizableBalance();

                // mixable balance is way too small
                if (nBalanceAnonymizable.isLessThan(nValueMin)) {
                    strAutoDenomResult = "Not enough funds to mix.";
                    log.info("CoinJoin{}", strAutoDenomResult);
                    return false;
                }

                // excluding denoms
                Coin nBalanceAnonimizableNonDenom = mixingWallet.getAnonymizableBalance(true);
                // denoms
                Coin nBalanceDenominatedConf = bal.getDenominatedTrusted();
                Coin nBalanceDenominatedUnconf = bal.getDenominatedUntrustedPending();
                Coin nBalanceDenominated = nBalanceDenominatedConf.add(nBalanceDenominatedUnconf);
                Coin nBalanceToDenominate = CoinJoinClientOptions.getAmount().subtract(nBalanceDenominated);

                // adjust nBalanceNeedsAnonymized to consume final denom
                if (nBalanceDenominated.subtract(nBalanceAnonymized).isGreaterThan(nBalanceNeedsAnonymized)) {
                    List<Coin> denoms = CoinJoin.getStandardDenominations();
                    Coin nAdditionalDenom = Coin.ZERO;
                    for (Coin denom :denoms){
                        if (nBalanceNeedsAnonymized.isLessThan(denom)) {
                            nAdditionalDenom = denom;
                        } else {
                            break;
                        }
                    }
                    nBalanceNeedsAnonymized = nBalanceNeedsAnonymized.add(nAdditionalDenom);
                }

                log.info("coinjoin: current stats:\n" +
                        "    nValueMin: {}\n" +
                        "    nBalanceAnonymizable: {}\n" +
                        "    nBalanceAnonymized: {}\n" +
                        "    nBalanceNeedsAnonymized: {}\n" +
                        "    nBalanceAnonimizableNonDenom: {}\n" +
                        "    nBalanceDenominatedConf: {}\n" +
                        "    nBalanceDenominatedUnconf: {}\n" +
                        "    nBalanceDenominated: {}\n" +
                        "    nBalanceToDenominate: {}\n",
                        nValueMin.toFriendlyString(),
                        nBalanceAnonymizable.toFriendlyString(),
                        nBalanceAnonymized.toFriendlyString(),
                        nBalanceNeedsAnonymized.toFriendlyString(),
                        nBalanceAnonimizableNonDenom.toFriendlyString(),
                        nBalanceDenominatedConf.toFriendlyString(),
                        nBalanceDenominatedUnconf.toFriendlyString(),
                        nBalanceDenominated.toFriendlyString(),
                        nBalanceToDenominate.toFriendlyString()
                );

                if (fDryRun) return true;

                // Check if we have should create more denominated inputs i.e.
                // there are funds to denominate and denominated balance does not exceed
                // max amount to mix yet.
                if (nBalanceAnonimizableNonDenom.isGreaterThanOrEqualTo(nValueMin.add(CoinJoin.getCollateralAmount())) && nBalanceToDenominate.isGreaterThan(Coin.ZERO)) {
                    createDenominated(nBalanceToDenominate);
                }

                //check if we have the collateral sized inputs
                if (!mixingWallet.hasCollateralInputs()) {
                    return !mixingWallet.hasCollateralInputs(false) && makeCollateralAmounts();
                }

                if (sessionID.get() != 0) {
                    strAutoDenomResult = "Mixing in progress...";
                    return false;
                }

                // Initial phase, find a Masternode
                // Clean if there is anything left from previous session
                unlockCoins();
                keyHolderStorage.returnAll();
                SetNull();

                // should be no unconfirmed denoms in non-multi-session mode
                if (!CoinJoinClientOptions.isMultiSessionEnabled () && nBalanceDenominatedUnconf.isGreaterThan(Coin.ZERO)){
                    strAutoDenomResult = "Found unconfirmed denominated outputs, will wait till they confirm to continue.";
                    log.info("coinjoin: {}", strAutoDenomResult);
                    return false;
                }

                //check our collateral and create new if needed
                StringBuilder strReason = new StringBuilder();
                if (txMyCollateral.isEmpty()) {
                    if (!createCollateralTransaction(txMyCollateral, strReason)) {
                        log.info("coinjoin: create collateral error: {}", strReason);
                        return false;
                    }
                } else {
                    if (!CoinJoin.isCollateralValid(txMyCollateral)) {
                        log.info("coinjoin: invalid collateral, recreating...");
                        if (!createCollateralTransaction(txMyCollateral, strReason)) {
                            log.info("coinjoin: create collateral error: {}", strReason);
                            return false;
                        }
                    }
                }
                // lock the funds we're going to use for our collateral
                for (TransactionInput txin :txMyCollateral.getInputs()){
                    mixingWallet.lockCoin(txin.getOutpoint());
                    outPointLocked.add(txin.getOutpoint());
                }
            } finally {
                lock.unlock();
            }
        }


        // Always attempt to join an existing queue
        if (joinExistingQueue(nBalanceNeedsAnonymized)) {
            return true;
        }

        // If we were unable to find/join an existing queue then start a new one.
        if (startNewQueue(nBalanceNeedsAnonymized)) return true;
        
        strAutoDenomResult = "No compatible Masternode found.";
        return false;
    }

    /// As a client, submit part of a future mixing transaction to a Masternode to start the process
    public boolean submitDenominate() {
        return false;
    }

    public boolean processPendingDsaRequest() {
        return false;
    }

    public boolean checkTimeout() {
        return false;
    }
}
