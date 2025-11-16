/*
 * Copyright 2013 Google Inc.
 * Copyright 2019 Andreas Schildbach
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

import java.math.BigInteger;
import java.util.HashMap;

/**
 * Network parameters used by the bitcoinj unit tests (and potentially your own). This lets you solve a block using
 * {@link Block#solve()} by setting difficulty to the easiest possible.
 */
public class UnitTestParams extends AbstractBitcoinNetParams {
    public static final int UNITNET_MAJORITY_WINDOW = 8;
    public static final int TESTNET_MAJORITY_REJECT_BLOCK_OUTDATED = 6;
    public static final int TESTNET_MAJORITY_ENFORCE_BLOCK_UPGRADE = 4;

    public UnitTestParams() {
        super();
        id = ID_UNITTESTNET;
        packetMagic = 0xcee2caff;
        addressHeader = 140;
        p2shHeader = 19;
        maxTarget = new BigInteger("00ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
        genesisBlock.setTime(Utils.currentTimeSeconds());
        genesisBlock.setDifficultyTarget(Block.EASIEST_DIFFICULTY_TARGET);
        genesisBlock.solve();
        port = 18833;
        interval = 10;
        dumpedPrivateKeyHeader = 239;
        targetTimespan = 200000000;  // 6 years. Just a very big number.
        spendableCoinbaseDepth = 5;
        subsidyDecreaseBlockCount = 100;
        dnsSeeds = null;
        addrSeeds = null;
        bip32HeaderP2PKHpub = 0x043587cf; // The 4 byte header that serializes in base58 to "tpub".
        bip32HeaderP2PKHpriv = 0x04358394; // The 4 byte header that serializes in base58 to "tprv"
        bip32HeaderP2WPKHpub = 0x045f1cf6; // The 4 byte header that serializes in base58 to "vpub".
        bip32HeaderP2WPKHpriv = 0x045f18bc; // The 4 byte header that serializes in base58 to "vprv"
        dip14HeaderP2PKHpub = 0x0eed270b; // The 4 byte header that serializes in base58 to "dptp".
        dip14HeaderP2PKHpriv = 0x0eed2774; // The 4 byte header that serializes in base58 to "dpts"
        majorityEnforceBlockUpgrade = 3;
        majorityRejectBlockOutdated = 4;
        majorityWindow = 7;

        DIP0001BlockHeight = 100000;  // not active
        strSporkAddress = "PM3E76WHM8MsRaWdDaoX2XWEnBoganZxjT";
        budgetPaymentsStartBlock = 4100;
        budgetPaymentsCycleBlocks = 50;
        budgetPaymentsWindowBlocks = 10;
        superblockStartBlock = 4200;
        superblockCycle = 24;

        powDGWHeight = 34140;
        powKGWHeight = 15200;

        instantSendConfirmationsRequired = MainNetParams.get().getInstantSendConfirmationsRequired();
        instantSendKeepLock = MainNetParams.get().getInstantSendKeepLock();

        DIP0003BlockHeight = Integer.MAX_VALUE;
        deterministicMasternodesEnabledHeight = Integer.MAX_VALUE;
        deterministicMasternodesEnabled = false;

        DIP0008BlockHeight = Integer.MAX_VALUE;
        DIP0024BlockHeight = Integer.MAX_VALUE;
        v19BlockHeight = Integer.MAX_VALUE;
        v20BlockHeight = Integer.MAX_VALUE;

        llmqChainLocks = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqForInstantSend = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqTypePlatform = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqTypeDIP0024InstantSend = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqTypeMnhf = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqTypeAssetLocks = LLMQParameters.LLMQType.LLMQ_NONE;

        BIP65Height = 2431; // 0000039cf01242c7f921dcb4806a5994bc003b48c1973ae0c89b67809c2bb2ab

        coinType = 1;
    }

    private static UnitTestParams instance;
    public static synchronized UnitTestParams get() {
        if (instance == null) {
            instance = new UnitTestParams();
        }
        return instance;
    }

    @Override
    public String getPaymentProtocolId() {
        return "unittest";
    }
}
