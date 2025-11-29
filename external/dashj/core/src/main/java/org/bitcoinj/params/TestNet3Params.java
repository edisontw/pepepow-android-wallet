/*
 * Copyright 2013 Google Inc.
 * Copyright 2014 Andreas Schildbach
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

import static org.bitcoinj.core.Utils.HEX;

import org.bitcoinj.core.*;
import org.bitcoinj.quorums.LLMQParameters;

import static com.google.common.base.Preconditions.checkState;

/**
 * Parameters for the testnet, a separate public instance of Dash that has relaxed rules suitable for development
 * and testing of applications and new Dash versions.
 */
public class TestNet3Params extends AbstractBitcoinNetParams {

    public static final int TESTNET_MAJORITY_WINDOW = 100;
    public static final int TESTNET_MAJORITY_REJECT_BLOCK_OUTDATED = 75;
    public static final int TESTNET_MAJORITY_ENFORCE_BLOCK_UPGRADE = 51;

    public TestNet3Params() {
        super();
        id = ID_TESTNET;

        packetMagic = 0xcee2caffL;
        interval = INTERVAL;
        targetTimespan = TARGET_TIMESPAN;

        // 00000fffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
        maxTarget = Utils.decodeCompactBits(0x1e0fffffL);
        maxTargetAfterSwitch = maxTarget;
        newHashHeight = 4;
        newHashBits = 0x2000ffffL;
        port = 18833;
        addressHeader = 140;
        p2shHeader = 19;
        dumpedPrivateKeyHeader = 239;
        genesisBlock.setTime(1683638200L);
        genesisBlock.setDifficultyTarget(0x1e0fffffL);
        genesisBlock.setNonce(413519);
        spendableCoinbaseDepth = 100;
        subsidyDecreaseBlockCount = 210240;
        String genesisHash = genesisBlock.getHashAsString();

        checkState(genesisHash.equals("000004ac806a95514c329a8cf20303e68b9257bdf29ea520173b5d09022863ac"));
        alertSigningKey = HEX.decode("04517d8a699cb43d3938d7b24faaff7cda448ca4ea267723ba614784de661949bf632d6304316b244646dea079735b9a6fc4af804efb4752075b9fe2245e14e412");

        dnsSeeds = new String[] {
                "82.163.79.208",
                "141.147.71.107",
                "132.145.54.241"
        };

        bip32HeaderP2PKHpub = 0x043587cf; // The 4 byte header that serializes in base58 to "tpub".
        bip32HeaderP2PKHpriv = 0x04358394; // The 4 byte header that serializes in base58 to "tprv"
        dip14HeaderP2PKHpub = 0x0eed270b; // The 4 byte header that serializes in base58 to "dptp".
        dip14HeaderP2PKHpriv = 0x0eed2774; // The 4 byte header that serializes in base58 to "dpts"


        checkpoints.clear();
        checkpoints.put(0, Sha256Hash.wrap("000004ac806a95514c329a8cf20303e68b9257bdf29ea520173b5d09022863ac"));

        // updated with Dash Core 20.0.0 seed list
        addrSeeds = new int[] {
                0xd04fa352,
                0x6b47938d,
                0xf1369184
        };

        bip32HeaderP2PKHpub = 0x043587cf;
        bip32HeaderP2PKHpriv = 0x04358394;

        strSporkAddress = "PM3E76WHM8MsRaWdDaoX2XWEnBoganZxjT";
        minSporkKeys = 1;
        budgetPaymentsStartBlock = 4100;
        budgetPaymentsCycleBlocks = 50;
        budgetPaymentsWindowBlocks = 10;

        majorityEnforceBlockUpgrade = TESTNET_MAJORITY_ENFORCE_BLOCK_UPGRADE;
        majorityRejectBlockOutdated = TESTNET_MAJORITY_REJECT_BLOCK_OUTDATED;
        majorityWindow = TESTNET_MAJORITY_WINDOW;

        DIP0001BlockHeight = 4400;

        fulfilledRequestExpireTime = 5 * 60;
        masternodeMinimumConfirmations = 1;
        superblockStartBlock = 4200;
        superblockCycle = 24;
        nGovernanceMinQuorum = 1;
        nGovernanceFilterElements = 500;

        powDGWHeight = 4001;
        powKGWHeight = 4001;
        powAllowMinimumDifficulty = true;
        powNoRetargeting = false;

        instantSendConfirmationsRequired = 2;
        instantSendKeepLock = 6;

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

        BIP34Height = 1;
        BIP65Height = 1;
        BIP66Height = 1;

        coinType = 1;
        assumeValidQuorums.add(Sha256Hash.wrap("0000000007697fd69a799bfa26576a177e817bc0e45b9fcfbf48b362b05aeff2"));
        assumeValidQuorums.add(Sha256Hash.wrap("000000339cd97d45ee18cd0cba0fd590fb9c64e127d3c30885e5b7376af94fdf"));
        assumeValidQuorums.add(Sha256Hash.wrap("0000007833f1b154218be64712cabe0e7c695867cc0c452311b2d786e14622fa"));
     }

    private static TestNet3Params instance;

    public static synchronized TestNet3Params get() {
        if (instance == null) {
            instance = new TestNet3Params();
        }
        return instance;
    }

    @Override
    public String getPaymentProtocolId() {
        return PAYMENT_PROTOCOL_ID_TESTNET;
    }

    public static String[] MASTERNODES = {
        "54.213.94.216",
        "35.165.156.159",
        "35.90.157.206",
        "35.91.197.218",
        "54.212.91.148",
        "54.202.231.195",
        "35.88.122.202",
        "54.186.145.18",
        "35.90.193.169",
        "34.212.161.186",
        "34.220.155.3",
        "54.212.138.75",
        "54.188.69.89",
        "54.190.131.8",
        "34.220.194.253",
        "54.191.28.44",
        "35.87.238.118",
        "35.90.217.208",
        "34.220.243.24",
        "35.161.222.74",
        "54.190.61.70",
        "34.210.26.195",
        "34.217.191.164",
        "54.189.125.235",
        "34.220.175.29",
        "52.36.20.123",
        "54.185.69.133",
        "54.68.48.149",
        "34.210.84.163",
        "54.202.190.181",
        "35.91.239.75",
        "34.222.21.14",
        "34.220.134.30",
        "35.90.252.3",
        "35.89.166.118",
        "18.237.170.32",
        "35.162.18.116",
        "35.91.208.56",
        "34.219.33.231",
        "52.34.250.214",
        "35.91.134.89",
        "50.112.58.114",
        "54.191.146.137",
        "34.218.66.37",
        "34.221.196.103",
        "35.91.157.30",
        "34.221.102.51",
        "18.237.165.242",
        "52.37.61.9",
        "54.212.89.127",
        "34.209.238.228",
        "35.92.143.7",
        "35.89.113.195",
        "52.12.54.89",
        "34.219.153.30",
        "34.215.171.237",
        "54.70.243.3",
        "54.184.126.25",
        "34.222.85.18",
        "34.221.252.179",
        "35.85.33.152",
        "54.200.220.105",
        "54.245.75.47",
        "54.214.59.174",
        "35.164.77.177",
        "35.89.66.84",
        "35.91.150.34",
        "35.92.219.124",
        "34.222.82.127",
        "34.220.171.156",
        "35.90.42.64",
        "35.89.53.128",
        "35.93.151.188",
        "34.211.172.212",
        "34.220.118.79",
        "34.220.187.233",
        "34.220.85.81",
        "35.167.165.224",
        "34.210.26.93",
        "35.90.53.180",
    };

    public String [] HP_MASTERNODES = {
        "34.214.48.68",
        "35.166.18.166",
        "35.165.50.126",
        "52.42.202.128",
        "52.12.176.90",
        "44.233.44.95",
        "35.167.145.149",
        "52.34.144.50",
        "44.240.98.102",
        "54.201.32.131",
        "52.10.229.11",
        "52.13.132.146",
        "44.228.242.181",
        "35.82.197.197",
        "52.40.219.41",
        "44.239.39.153",
        "54.149.33.167",
        "35.164.23.245",
        "52.33.28.47",
        "52.43.86.231",
        "52.43.13.92",
        "35.163.144.230",
        "52.89.154.48",
        "52.24.124.162",
        "44.227.137.77",
        "35.85.21.179",
        "54.187.14.232",
        "54.68.235.201",
        "52.13.250.182",
        "35.82.49.196",
        "44.232.196.6",
        "54.189.164.39",
        "54.213.204.85",
    };

    @Override
    public String[] getDefaultMasternodeList() {
        return MASTERNODES;
    }

    @Override
    public String[] getDefaultHPMasternodeList() {
        return HP_MASTERNODES;
    }
}
