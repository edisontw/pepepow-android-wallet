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
        dumpedPrivateKeyHeader = 204;
        addressHeader = 55;
        p2shHeader = 16;
        port = 8832;
        packetMagic = 0xbf0c6bbdL;
        bip32HeaderP2PKHpub = 0x0488b21e; // The 4 byte header that serializes in base58 to "xpub".
        bip32HeaderP2PKHpriv = 0x0488ade4; // The 4 byte header that serializes in base58 to "xprv"
        dip14HeaderP2PKHpub = 0x0eecefc5; // The 4 byte header that serializes in base58 to "dpmp".
        dip14HeaderP2PKHpriv = 0x0eecf02e; // The 4 byte header that serializes in base58 to "dpms"

        genesisBlock.setDifficultyTarget(0x1e0fffffL);
        genesisBlock.setTime(1683850602L);
        genesisBlock.setNonce(283486);

        majorityEnforceBlockUpgrade = MAINNET_MAJORITY_ENFORCE_BLOCK_UPGRADE;
        majorityRejectBlockOutdated = MAINNET_MAJORITY_REJECT_BLOCK_OUTDATED;
        majorityWindow = MAINNET_MAJORITY_WINDOW;

        id = ID_MAINNET;
        subsidyDecreaseBlockCount = 210240;
        spendableCoinbaseDepth = 100;
        String genesisHash = genesisBlock.getHashAsString();
        checkState(genesisHash.equals("00000a308cc3b469703a3bc1aa55bc251a71c9287d7b413242592c0ab0a31f13"),
                genesisHash);

        dnsSeeds = new String[] {
                "dnsseed.pepepow.org",
                "dnsseed.pepepow.foztor.net"
        };

        // This contains (at a minimum) the blocks which are not BIP30 compliant. BIP30 changed how duplicate
        // transactions are handled. Duplicated transactions could occur in the case where a coinbase had the same
        // extraNonce and the same outputs but appeared at different heights, and greatly complicated re-org handling.
        // Having these here simplifies block connection logic considerably.
        checkpoints.put(0, Sha256Hash.wrap("00000a308cc3b469703a3bc1aa55bc251a71c9287d7b413242592c0ab0a31f13"));
        checkpoints.put(1920000, Sha256Hash.wrap("000000000018cef21f56b393e0fbb3c5b28c77f0a6134ea2c0424fc4fe937fdc"));

        // Dash does not have a Http Seeder
        // If an Http Seeder is set up, add it here.  References: HttpDiscovery
        httpSeeds = null;

        addrSeeds = new int[] {
                0x921c2565,
                0xf3d4cd6d,
                0xa0f32479,
                0x911d9184,
                0x6b47938d,
                0x3a49938d,
                0x9d5a628d,
                0x0517be92,
                0x9a2df59d,
                0x65ade3a5,
                0x006baca7,
                0x48fd63a7,
                0xd10568ac,
                0x0ce380b2,
                0x01382db5,
                0x645c2bb6,
                0x0fc80cba,
                0x7dd72ec0,
                0xc1eebc05,
                0x32970905,
                0x5a83a133,
                0x60312736,
                0x0b0cf9ad,
                0xcfe3b4cf,
                0x025b56a7,
                0x365756a7,
                0x945556a7,
                0xf07c56a7,
                0x9a21d712,
                0x5b0fef17,
                0xaf6b7ac1,
                0x7c39280d
        };

        strSporkAddress = "PM3E76WHM8MsRaWdDaoX2XWEnBoganZxjT";
        minSporkKeys = 1;
        budgetPaymentsStartBlock = 328008;
        budgetPaymentsCycleBlocks = 16616;
        budgetPaymentsWindowBlocks = 100;

        DIP0001BlockHeight = 782208;

        fulfilledRequestExpireTime = 60*60;
        masternodeMinimumConfirmations = 15;
        superblockStartBlock = 120000;
        superblockCycle = 16616;
        nGovernanceMinQuorum = 10;
        nGovernanceFilterElements = 20000;

        powDGWHeight = 1;
        powKGWHeight = 15200;
        powAllowMinimumDifficulty = false;
        powNoRetargeting = false;

        instantSendConfirmationsRequired = 6;
        instantSendKeepLock = 24;

        DIP0003BlockHeight = Integer.MAX_VALUE;
        deterministicMasternodesEnabledHeight = Integer.MAX_VALUE;
        deterministicMasternodesEnabled = false;

        DIP0008BlockHeight = Integer.MAX_VALUE;
        DIP0024BlockHeight = Integer.MAX_VALUE;
        v19BlockHeight = Integer.MAX_VALUE;
        v20BlockHeight = Integer.MAX_VALUE;

        // PEPEPOW core does not use LLMQ/chainlocks, so disable them.
        llmqChainLocks = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqForInstantSend = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqTypePlatform = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqTypeDIP0024InstantSend = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqTypeMnhf = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqTypeAssetLocks = LLMQParameters.LLMQType.LLMQ_NONE;

        BIP34Height = 1;
        BIP65Height = 1;
        BIP66Height = 1;

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
