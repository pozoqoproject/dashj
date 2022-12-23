/*
 * Copyright 2022 Dash Core Group
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

import com.google.common.collect.Lists;
import org.bitcoinj.coinjoin.utils.CoinJoinManager;
import org.bitcoinj.coinjoin.utils.CoinJoinResult;
import org.bitcoinj.coinjoin.utils.ProTxToOutpoint;
import org.bitcoinj.coinjoin.utils.RelayTransaction;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.GetDataMessage;
import org.bitcoinj.core.GetSporksMessage;
import org.bitcoinj.core.InventoryMessage;
import org.bitcoinj.core.KeyId;
import org.bitcoinj.core.MasternodeAddress;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.PartialMerkleTree;
import org.bitcoinj.core.PrunedException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.BLSLazyPublicKey;
import org.bitcoinj.crypto.BLSSecretKey;
import org.bitcoinj.evolution.SimplifiedMasternodeListDiff;
import org.bitcoinj.evolution.SimplifiedMasternodeListEntry;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.testing.InboundMessageQueuer;
import org.bitcoinj.testing.MockTransactionBroadcaster;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.DerivationPathFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(value = Parameterized.class)
public class CoinJoinSessionTest extends TestWithMasternodeGroup {
    Logger log = LoggerFactory.getLogger(CoinJoinSessionTest.class);
    @Rule
    public ExpectedException thrown = ExpectedException.none();
    private static final int SESSION_ID = 123456;

    // Information for a single masternode
    CoinJoinServer coinJoinServer;
    SimplifiedMasternodeListEntry entry;
    TransactionOutPoint masternodeOutpoint;
    BLSSecretKey operatorSecretKey;
    private Address coinbaseTo;
    private Transaction finalTx = null;
    private Transaction mixingTx = null;

    @Parameterized.Parameters
    public static Collection<ClientType[]> parameters() {
        return Arrays.asList(new ClientType[]{ClientType.NIO_CLIENT_MANAGER},
                new ClientType[]{ClientType.BLOCKING_CLIENT_MANAGER});
    }

    public CoinJoinSessionTest(ClientType clientType) {
        super(clientType);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();

        BriefLogFormatter.initVerbose();
        Utils.setMockClock(); // Use mock clock
        ProTxToOutpoint.initialize(UNITTEST);
        wallet.freshReceiveKey();

        coinbaseTo = Address.fromKey(UNITTEST, wallet.currentReceiveKey());
        operatorSecretKey = BLSSecretKey.fromSeed(Sha256Hash.ZERO_HASH.getBytes());
        coinJoinServer = new CoinJoinServer(wallet.getContext());

        entry = new SimplifiedMasternodeListEntry(
                UNITTEST,
                Sha256Hash.ZERO_HASH,
                Sha256Hash.ZERO_HASH,
                new MasternodeAddress("127.0.0.1", 2003),
                new KeyId(new ECKey().getPubKeyHash()),
                new BLSLazyPublicKey(operatorSecretKey.GetPublicKey()),
                true
        );

        masternodeOutpoint = new TransactionOutPoint(UNITTEST, 0, Sha256Hash.ZERO_HASH);

        Block nextBlock = blockChain.getChainHead().getHeader().createNextBlock(coinbaseTo,
                1,
                Utils.currentTimeSeconds(),
                blockChain.getBestChainHeight() + 1,
                entry.getHash(), entry.getHash());
        blockChain.add(nextBlock);

        // this will add one masternode to our masternode list
        Transaction coinbase = nextBlock.getTransactions().get(0);
        SimplifiedMasternodeListDiff diff = new SimplifiedMasternodeListDiff(UNITTEST,
                nextBlock.getPrevBlockHash(),
                nextBlock.getHash(),
                new PartialMerkleTree(
                        UNITTEST,
                        new byte[]{0},
                        new ArrayList<>(Collections.singletonList(nextBlock.getTransactions().get(0).getTxId())),
                        1),
                coinbase,
                Collections.singletonList(entry),
                Collections.emptyList()
        );
        wallet.getContext().masternodeListManager.processMasternodeListDiff(null, diff, true);

        wallet.getContext().coinJoinManager.setMasternodeGroup(masternodeGroup);
        MockTransactionBroadcaster broadcaster = new MockTransactionBroadcaster(wallet);
        wallet.setTransactionBroadcaster(broadcaster);

        // the first block needs to mature before we can spend it, so mine 100 more blocks on the blockchain
        for (int i = 0; i < 100; ++i) {
            addBlock();
        }

        globalTimeout = Timeout.seconds(30);
    }

    @Override
    @After
    public void tearDown() {
        super.tearDown();
    }

    @Test//(timeout = 30000) // Exception: test timed out after 100 milliseconds
    @Ignore
    public void sessionTest() throws Exception {
        System.out.println("Session test started...");
        wallet.addCoinJoinKeyChain(DerivationPathFactory.get(wallet.getParams()).coinJoinDerivationPath());
        CoinJoinClientOptions.reset();
        CoinJoinClientOptions.setAmount(Coin.COIN);
        CoinJoinClientOptions.setEnabled(true);
        CoinJoinClientOptions.setRounds(1);
        CoinJoinClientOptions.setSessions(1);

        peerGroup.start();
        masternodeGroup.start();
        InboundMessageQueuer p1 = connectPeer(1);
        InboundMessageQueuer p2 = connectPeer(2);
        assertEquals(2, peerGroup.numConnectedPeers());

        CoinJoinManager coinJoinManager = wallet.getContext().coinJoinManager;

        coinJoinManager.coinJoinClientManagers.put(wallet.getDescription(), new CoinJoinClientManager(wallet));

        HashMap<TransactionOutPoint, ECKey> keyMap = new HashMap<>();

        // mix coins
        CoinJoinClientManager clientManager = coinJoinManager.coinJoinClientManagers.get(wallet.getDescription());
        clientManager.setBlockChain(wallet.getContext().blockChain);

        addBlock();
        clientManager.updatedSuccessBlock();

        if (!clientManager.startMixing()) {
            System.out.println("Mixing has been started already.");
            return;
        }

        boolean result = clientManager.doAutomaticDenominating();
        System.out.println("Mixing " + (result ? "started successfully" : ("start failed: " + clientManager.getStatuses() + ", will retry")));

        for (int i = 0; i < 5; ++i) {
            addBlock();
        }

        boolean breakOut = false;
        CoinJoinQueue queue = null;
        coinJoinServer.setRelayTransaction(relayTransaction);

        // this loop with a sleep of 1 second will simulate a node that is attempting to
        // mix 1 session (1 round)
        do {
            Thread.sleep(1000);
            wallet.getContext().coinJoinManager.doMaintenance();
            addBlock();

            // this section of nextMessage() and if/else blocks will simulate a mixing masternode
            // it will reply to the messages sent by doMainenance()
            if (lastMasternode == null)
                continue; // try again in one second

            Message m = lastMasternode.nextMessage();
            log.info("received message: {}", m);
            System.out.println("masternode has received message: " + m);
            if (m instanceof GetSporksMessage) {
                m = lastMasternode.nextMessage();
            }

            if (m instanceof CoinJoinAccept) {
                // process the dsa message
                CoinJoinAccept dsa = (CoinJoinAccept) m;
                CoinJoinResult acceptable = coinJoinServer.isAcceptableDSA(dsa);
                coinJoinServer.processAccept(p1.peer, dsa);
                assertTrue(acceptable.getMessage(), acceptable.isSuccess());
                CoinJoinStatusUpdate update = new CoinJoinStatusUpdate(m.getParams(), SESSION_ID, PoolState.POOL_STATE_QUEUE, PoolStatusUpdate.STATUS_ACCEPTED, PoolMessage.MSG_NOERR);
                coinJoinManager.processMessage(lastMasternode.peer, update);

                // initialize Server
                coinJoinServer.setSession(SESSION_ID);
                coinJoinServer.setDenomination(dsa.getDenomination());

                // send the dsq message indicating the mixing session is ready
                queue = new CoinJoinQueue(m.getParams(), dsa.getDenomination(), masternodeOutpoint, Utils.currentTimeSeconds(), true);
                queue.sign(operatorSecretKey);
                coinJoinManager.processMessage(lastMasternode.peer, queue);
            } else if (m instanceof CoinJoinEntry) {
                CoinJoinEntry entry = (CoinJoinEntry) m;
                log.info("CoinJoinEntry received: {}", entry.toString(true));

                coinJoinServer.processEntry(p1.peer, entry);

                CoinJoinStatusUpdate update = new CoinJoinStatusUpdate(m.getParams(), SESSION_ID, PoolState.POOL_STATE_ACCEPTING_ENTRIES, PoolStatusUpdate.STATUS_ACCEPTED, PoolMessage.MSG_ENTRIES_ADDED);
                coinJoinManager.processMessage(lastMasternode.peer, update);

                finalTx = coinJoinServer.createFinalTransaction();//new Transaction(UNITTEST);

                /*for (TransactionInput input : entry.getMixingInputs()) {
                    finalTx.addInput(input);
                }

                for (TransactionOutput output : entry.getMixingOutputs()) {
                    finalTx.addOutput(output);
                }

                for (int i = 0; i < 10 - entry.getMixingOutputs().size(); i++) {
                    finalTx.addOutput(CoinJoin.denominationToAmount(queue.getDenomination()), Address.fromKey(UNITTEST, new ECKey()));
                    byte[] txId = new byte[32];
                    random.nextBytes(txId);
                    TransactionOutPoint outPoint = new TransactionOutPoint(UNITTEST, random.nextInt(10), Sha256Hash.wrap(txId));
                    TransactionInput input = new TransactionInput(UNITTEST, null, new byte[]{}, outPoint);
                    finalTx.addInput(input);
                }*/
                assertTrue(coinJoinServer.validateFinalTransaction(Lists.newArrayList(entry), finalTx));
                ValidInOuts validState = coinJoinServer.isValidInOuts(finalTx.getInputs(), finalTx.getOutputs());
                assertTrue(validState.messageId.name(), validState.result);
                CoinJoinFinalTransaction finalTxMessage = new CoinJoinFinalTransaction(m.getParams(), SESSION_ID, finalTx);
                coinJoinManager.processMessage(lastMasternode.peer, finalTxMessage);

                update = new CoinJoinStatusUpdate(m.getParams(), SESSION_ID, PoolState.POOL_STATE_SIGNING, PoolStatusUpdate.STATUS_ACCEPTED, PoolMessage.MSG_NOERR);
                coinJoinManager.processMessage(lastMasternode.peer, update);
            } else if (m instanceof CoinJoinSignedInputs) {
                CoinJoinSignedInputs signedInputs = (CoinJoinSignedInputs) m;
                CoinJoinComplete completeMessage = new CoinJoinComplete(UNITTEST, SESSION_ID, PoolMessage.MSG_SUCCESS);
                coinJoinManager.processMessage(lastMasternode.peer, completeMessage);

                // sign the rest of the inputs?
                mixingTx = new Transaction(UNITTEST);
                mixingTx.setVersion(finalTx.getVersion());
                mixingTx.setLockTime(finalTx.getLockTime());
                for (TransactionOutput output : finalTx.getOutputs()) {
                    mixingTx.addOutput(output);
                }
                for (TransactionInput input : finalTx.getInputs()) {
                    TransactionInput thisSignedInput = null;
                    for (TransactionInput signedInput : signedInputs.getInputs()) {
                        if (signedInput.getOutpoint().equals(input.getOutpoint())) {
                            thisSignedInput = signedInput;
                        }
                    }

                    if (thisSignedInput != null) {
                        mixingTx.addInput(thisSignedInput);
                    } else {
                        ECKey signingKey = new ECKey();
                        Address address = Address.fromKey(UNITTEST, signingKey);
                        mixingTx.addSignedInput(input, ScriptBuilder.createOutputScript(address), signingKey, Transaction.SigHash.ALL, false);
                    }
                }

                CoinJoinBroadcastTx broadcastTx = new CoinJoinBroadcastTx(UNITTEST, mixingTx, masternodeOutpoint, Utils.currentTimeSeconds());
                broadcastTx.sign(operatorSecretKey);

                coinJoinManager.processMessage(lastMasternode.peer, broadcastTx);

                breakOut = true;
            }
        } while (!breakOut);

        System.out.println("loop complete");
        // check that the queue for the session is valid
        assertNotNull(queue);

        // check that the mixing transaction was completed
        assertNotNull(mixingTx);

        // check that the broadcastTx was processed
        CoinJoinBroadcastTx broadcastTx = CoinJoin.getDSTX(mixingTx.getTxId());
        assertNotNull(broadcastTx);

        if (clientManager.isMixing()) {
            clientManager.stopMixing();
        }

    }

    @Test(timeout = 30000) // Exception: test timed out after 100 milliseconds
    public void sessionTestTwo() throws Exception {
        System.out.println("Session test started...");
        wallet.addCoinJoinKeyChain(DerivationPathFactory.get(wallet.getParams()).coinJoinDerivationPath());
        CoinJoinClientOptions.reset();
        CoinJoinClientOptions.setAmount(Coin.COIN);
        CoinJoinClientOptions.setEnabled(true);
        CoinJoinClientOptions.setRounds(1);
        CoinJoinClientOptions.setSessions(1);

        peerGroup.start();
        masternodeGroup.start();
        InboundMessageQueuer spvClient = connectPeer(1);
        InboundMessageQueuer someNode = connectPeer(2);
        assertEquals(2, peerGroup.numConnectedPeers());
        assertEquals(GetSporksMessage.class, outbound(spvClient).getClass());
        assertEquals(GetSporksMessage.class, outbound(someNode).getClass());

        CoinJoinManager coinJoinManager = wallet.getContext().coinJoinManager;

        coinJoinManager.coinJoinClientManagers.put(wallet.getDescription(), new CoinJoinClientManager(wallet));

        // mix coins
        CoinJoinClientManager clientManager = coinJoinManager.coinJoinClientManagers.get(wallet.getDescription());
        clientManager.setBlockChain(wallet.getContext().blockChain);

        addBlock();
        clientManager.updatedSuccessBlock();

        if (!clientManager.startMixing()) {
            System.out.println("Mixing has been started already.");
            return;
        }

        boolean result = clientManager.doAutomaticDenominating();
        System.out.println("Mixing " + (result ? "started successfully" : ("start failed: " + clientManager.getStatuses() + ", will retry")));

        for (int i = 0; i < 5; ++i) {
            addBlock();
        }

        coinJoinServer.setRelayTransaction(relayTransaction);

        // step 0: client will connect to the masternode, if it hasn't yet
        do {
            Thread.sleep(1000);
            wallet.getContext().coinJoinManager.doMaintenance();
        } while (lastMasternode == null);

        addBlock();
        assertNotNull(lastMasternode);

        // step 1a: masternode receives CoinJoinAccept (dsa) message
        Message acceptMessage = outbound(lastMasternode);
        assertEquals(CoinJoinAccept.class, acceptMessage.getClass());

        // step 1b: masternode processes the dsa message
        CoinJoinAccept dsa = (CoinJoinAccept) acceptMessage;
        CoinJoinResult acceptable = coinJoinServer.isAcceptableDSA(dsa);
        assertTrue(acceptable.getMessage(), acceptable.isSuccess());
        coinJoinServer.processAccept(spvClient.peer, dsa);

        // step 2: client receives the CoinJoinStatusUpdate(dssu) message
        Message updateMessage = outbound(spvClient);
        assertEquals(CoinJoinStatusUpdate.class, updateMessage.getClass());
        int sessionId = ((CoinJoinStatusUpdate) updateMessage).getSessionID();
        // step 2: client processes the dssu message
        coinJoinManager.processMessage(lastMasternode.peer, updateMessage);

        // step 3: masternode checks that there are enough users for a session
        coinJoinServer.checkForCompleteQueue();
        // step 3: client receives a CoinJoinQueue (dsq) message with the ready=true flag
        Message queueMessage = outbound(spvClient);
        assertEquals(CoinJoinQueue.class, queueMessage.getClass());
        // step 3: client processes the dsq message
        coinJoinManager.processMessage(lastMasternode.peer, queueMessage);
        coinJoinManager.doMaintenance();

        // step 4: masternode receives the CoinJoinEntry (dsi) message
        Message entryMessage = outbound(lastMasternode);
        assertEquals(CoinJoinEntry.class, entryMessage.getClass());
        // step 4: masternode processes the dsi message
        CoinJoinEntry entry = (CoinJoinEntry) entryMessage;
        coinJoinServer.processEntry(spvClient.peer, entry);

        // step 5: client receives a CoinJoinStatusUpdate (dssu) message
        updateMessage = outbound(spvClient);
        assertEquals(CoinJoinStatusUpdate.class, updateMessage.getClass());
        // step 5: client processes the dssu message
        coinJoinManager.processMessage(lastMasternode.peer, updateMessage);
        coinJoinManager.doMaintenance();

        // step 6: client receives the CoinJoinFinalTransaction (dsf) message
        Message finalTxMessage = outbound(spvClient);
        assertEquals(finalTxMessage.toString(), CoinJoinFinalTransaction.class, finalTxMessage.getClass());
        // step 6: client processes the CoinJoinFinalTransaction (dsf) message
        CoinJoinFinalTransaction dsf = (CoinJoinFinalTransaction) finalTxMessage;
        Transaction finalTx = dsf.getTransaction();
        assertEquals(sessionId, dsf.getMsgSessionID());
        assertTrue(coinJoinServer.validateFinalTransaction(Lists.newArrayList(entry), finalTx));
        ValidInOuts validState = coinJoinServer.isValidInOuts(finalTx.getInputs(), finalTx.getOutputs());
        assertTrue(validState.messageId.name(), validState.result);
        coinJoinManager.processMessage(lastMasternode.peer, finalTxMessage);

        // step 7: client receives a CoinJoinStatusUpdate (dssu) message
        updateMessage = outbound(spvClient);
        assertEquals(CoinJoinStatusUpdate.class, updateMessage.getClass());
        assertEquals(sessionId, ((CoinJoinStatusUpdate) updateMessage).getSessionID());
        // step 7: client receives a CoinJoinStatusUpdate (dssu) message
        coinJoinManager.processMessage(lastMasternode.peer, updateMessage);
        coinJoinManager.doMaintenance();


        // step 8: masternode receives the CoinJoinSignedInputs dss message
        Message signedInputsMessage = outbound(lastMasternode);
        assertEquals(CoinJoinSignedInputs.class, signedInputsMessage.getClass());
        // step 8: masternode processes the CoinJoinSignedInputs dss message
        CoinJoinSignedInputs signedInputs = (CoinJoinSignedInputs) signedInputsMessage;
        coinJoinServer.processSignedInputs(spvClient.peer, signedInputs);

        // step 8: client receives the CoinJoinComplete (dsc) message
        Message completeMessage = outbound(spvClient);
        assertEquals(CoinJoinComplete.class, completeMessage.getClass());
        assertEquals(sessionId, ((CoinJoinComplete) completeMessage).getMsgSessionID());
        // step 8: client processes the dsc message
        coinJoinManager.processMessage(lastMasternode.peer, completeMessage);

        // step 10: client receives inv message
        Message invMessage = outbound(spvClient);
        assertEquals(InventoryMessage.class, invMessage.getClass());
        InventoryMessage inv = (InventoryMessage) invMessage;
        assertEquals(1, inv.getItems().size());
        // step 10: client requests the dstx
        GetDataMessage getDataMessage = new GetDataMessage(wallet.getContext().getParams());
        getDataMessage.addItem(inv.getItems().get(0));

        // step 10: masternode processes request for getdata
        CoinJoinBroadcastTx dstx = CoinJoin.getDSTX(getDataMessage.getItems().get(0).hash);
        spvClient.peer.sendMessage(dstx);

        // step 11: client receives the CoinJoinBroadcastTx (dstx) message
        Message broadcastTxMessage = outbound(spvClient);
        assertEquals(CoinJoinBroadcastTx.class, broadcastTxMessage.getClass());
        mixingTx = ((CoinJoinBroadcastTx) broadcastTxMessage).getTx();

        CoinJoinBroadcastTx broadcastTxTwo = CoinJoin.getDSTX(mixingTx.getTxId());
        assertNotNull(broadcastTxTwo);

        addBlock();

        // TODO check wallet balance types here

        if (clientManager.isMixing()) {
            clientManager.stopMixing();
        }
    }

    private final ArrayList<Transaction> memPool = Lists.newArrayList();
    RelayTransaction relayTransaction = memPool::add;

    // performs the function of a miner
    private void addBlock() throws PrunedException {
        log.info("Mining a new block {} with {} txes", blockChain.getBestChainHeight() + 1, wallet.getPendingTransactions());
        Block nextBlock = blockChain.getChainHead().getHeader().createNextBlock(Address.fromKey(UNITTEST, new ECKey()), Block.BLOCK_VERSION_GENESIS, Utils.currentTimeSeconds() + blockChain.getBestChainHeight() * 2L, blockChain.getBestChainHeight() + 1);
        for (Transaction tx : wallet.getPendingTransactions()) {
            nextBlock.addTransaction(tx);
        }
        for (Transaction tx : memPool) {
            nextBlock.addTransaction(tx);
        }
        memPool.clear();
        nextBlock.solve();
        blockChain.add(nextBlock);
    }
}
