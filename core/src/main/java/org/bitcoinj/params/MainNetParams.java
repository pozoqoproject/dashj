/*
 * Copyright 2013 Google Inc.
 * Copyright 2015 Andreas Schildbach
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

package org.bitcoinj.params;

import org.bitcoinj.core.*;
import org.bitcoinj.quorums.LLMQParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.HashMap;

import static com.google.common.base.Preconditions.*;

/**
 * Parameters for the main production network on which people trade goods and services.
 */
public class MainNetParams extends AbstractBitcoinNetParams {
    private static final Logger log = LoggerFactory.getLogger(MainNetParams.class);

    public static final int MAINNET_MAJORITY_WINDOW = 1000;
    public static final int MAINNET_MAJORITY_REJECT_BLOCK_OUTDATED = 950;
    public static final int MAINNET_MAJORITY_ENFORCE_BLOCK_UPGRADE = 750;

    public MainNetParams() {
        super();
        interval = INTERVAL;
        targetTimespan = TARGET_TIMESPAN;

        // 00000fffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
        maxTarget = Utils.decodeCompactBits(0x1e0fffffL);
        dumpedPrivateKeyHeader = 183;
        addressHeader = 55;
        p2shHeader = 117;
        port = 40999;
        packetMagic = 0xeebefebd;
        bip32HeaderP2PKHpub = 0x0488b21e; // The 4 byte header that serializes in base58 to "xpub".
        bip32HeaderP2PKHpriv = 0x0488ade4; // The 4 byte header that serializes in base58 to "xprv"
        dip14HeaderP2PKHpub = 0x0eecefc5; // The 4 byte header that serializes in base58 to "dpmp".
        dip14HeaderP2PKHpriv = 0x0eecf02e; // The 4 byte header that serializes in base58 to "dpms"

        genesisBlock.setDifficultyTarget(0x1e0ffff0L);
        genesisBlock.setTime(1676218176L);
        genesisBlock.setNonce(30188923);

        majorityEnforceBlockUpgrade = MAINNET_MAJORITY_ENFORCE_BLOCK_UPGRADE;
        majorityRejectBlockOutdated = MAINNET_MAJORITY_REJECT_BLOCK_OUTDATED;
        majorityWindow = MAINNET_MAJORITY_WINDOW;

        id = ID_MAINNET;
        subsidyDecreaseBlockCount = 210240;
        spendableCoinbaseDepth = 100;
        String genesisHash = genesisBlock.getHashAsString();
        checkState(genesisHash.equals("00000c9a4d546d148187c142944cf4045de11d4bb4d6306435f1a4b420c44285"),
                genesisHash);

        dnsSeeds = new String[] {
                "129.151.165.124"
        };

        // This contains (at a minimum) the blocks which are not BIP30 compliant. BIP30 changed how duplicate
        // transactions are handled. Duplicated transactions could occur in the case where a coinbase had the same
        // extraNonce and the same outputs but appeared at different heights, and greatly complicated re-org handling.
        // Having these here simplifies block connection logic considerably.
        checkpoints.put(  1,    Sha256Hash.wrap("00000648eb31922308dfb7b561a53dd89601ecc7790848cc69c8cf24cf93c8e4"));
        checkpoints.put(  10,   Sha256Hash.wrap("00000b789ab605a84769b7616fff73677f83117bfd351195b7ea3720717dd25c"));
        checkpoints.put(  100,  Sha256Hash.wrap("0000055e010d5de3f8283eec89f1cad40c77a8b52d78c6a13818a2bbc96e1f42"));
        checkpoints.put(  300,  Sha256Hash.wrap("00000966010cdeedf882fa87a22cce50b5d1045c0ac7e2a41330e99db12c3c35"));
        checkpoints.put(  1500, Sha256Hash.wrap("000000a263fc497f9854995dca41aab1788967b0093cb71b403cfa08251ec8d6"));
        checkpoints.put(  2000, Sha256Hash.wrap("00000bd1d9e66d1a23ed2d53f66e88e61c0cf6af5386bdbba8a63ec64a24ae05"));
        // Dash does not have a Http Seeder
        // If an Http Seeder is set up, add it here.  References: HttpDiscovery
        httpSeeds = null;

        // updated with Dash Core 0.17.0.3 seed list
        addrSeeds = new int[] {
                0xa2130fc2,
                0x081c448a,
		0x9e65d77a,
		0x037e37b9,
		0x57976721,
		0xa2130fc0,
		0xcc0cc47a,
		0xa2130fc0,
		0x8f2a1e6f,
		0xac695688,
		0x51a99a0a
        };


        strSporkAddress = "PTHvArEmTAk13Mc9R9QVGhzW3vwnc46S7c";
        minSporkKeys = 1;
        budgetPaymentsStartBlock = 1000;
        budgetPaymentsCycleBlocks = 16616;
        budgetPaymentsWindowBlocks = 100;

        DIP0001BlockHeight = 100;

        fulfilledRequestExpireTime = 60*60;
        masternodeMinimumConfirmations = 15;
        superblockStartBlock = 1000;
        superblockCycle = 16616;
        nGovernanceMinQuorum = 10;
        nGovernanceFilterElements = 20000;

        powAllowMinimumDifficulty = false;
        powNoRetargeting = false;

        instantSendConfirmationsRequired = 6;
        instantSendKeepLock = 24;

        DIP0003BlockHeight = 1028160;
        deterministicMasternodesEnabledHeight = 1047200;
        deterministicMasternodesEnabled = true;

        DIP0008BlockHeight = 1088640;
        DIP0024BlockHeight = Integer.MAX_VALUE;

        // long living quorum params
        addLLMQ(LLMQParameters.LLMQType.LLMQ_50_60);
        addLLMQ(LLMQParameters.LLMQType.LLMQ_400_60);
        addLLMQ(LLMQParameters.LLMQType.LLMQ_400_85);
        addLLMQ(LLMQParameters.LLMQType.LLMQ_100_67);
        addLLMQ(LLMQParameters.LLMQType.LLMQ_60_75);
        llmqChainLocks = LLMQParameters.LLMQType.LLMQ_400_60;
        llmqForInstantSend = LLMQParameters.LLMQType.LLMQ_50_60;
        llmqTypePlatform = LLMQParameters.LLMQType.LLMQ_100_67;
        llmqTypeDIP0024InstantSend = LLMQParameters.LLMQType.LLMQ_60_75;
        llmqTypeMnhf = LLMQParameters.LLMQType.LLMQ_400_85;

        BIP34Height = 951;    // 000001f35e70f7c5705f64c6c5cc3dea9449e74d5b5c7cf74dad1bcca14a8012
        BIP65Height = 619382; // 00000000000076d8fcea02ec0963de4abfd01e771fec0863f960c2c64fe6f357
        BIP66Height = 245817;

        coinType = 5;
    }

    private static MainNetParams instance;
    public static synchronized MainNetParams get() {
        if (instance == null) {
            instance = new MainNetParams();
        }
        return instance;
    }

    @Override
    public String getPaymentProtocolId() {
        return PAYMENT_PROTOCOL_ID_MAINNET;
    }

    @Override
    protected void verifyDifficulty(StoredBlock storedPrev, Block nextBlock, BigInteger newTarget) {

        long newTargetCompact = calculateNextDifficulty(storedPrev, nextBlock, newTarget);
        long receivedTargetCompact = nextBlock.getDifficultyTarget();
        int height = storedPrev.getHeight() + 1;

        // On mainnet before block 68589: incorrect proof of work (DGW pre-fork)
        // see ContextualCheckBlockHeader in src/validation.cpp in Core repo (dashpay/dash)
        String msg = "Network provided difficulty bits do not match what was calculated: " +
                Long.toHexString(newTargetCompact) + " vs " + Long.toHexString(receivedTargetCompact);
        if (height <= 68589) {
            double n1 = convertBitsToDouble(receivedTargetCompact);
            double n2 = convertBitsToDouble(newTargetCompact);

            if (java.lang.Math.abs(n1 - n2) > n1 * 0.5 )
                throw new VerificationException(msg);
        } else {
            if (newTargetCompact != receivedTargetCompact)
                throw new VerificationException(msg);
        }
    }

    static double convertBitsToDouble(long nBits) {
        long nShift = (nBits >> 24) & 0xff;

        double dDiff =
                (double)0x0000ffff / (double)(nBits & 0x00ffffff);

        while (nShift < 29)
        {
            dDiff *= 256.0;
            nShift++;
        }
        while (nShift > 29)
        {
            dDiff /= 256.0;
            nShift--;
        }

        return dDiff;
    }
}
