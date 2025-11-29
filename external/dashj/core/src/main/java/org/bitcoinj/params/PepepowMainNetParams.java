/*
 * Copyright 2023 Pepepow Developers
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

import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Utils;
import org.bitcoinj.quorums.LLMQParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;

import static com.google.common.base.Preconditions.checkState;

/**
 * Parameters for the Pepepow main production network.
 */
public class PepepowMainNetParams extends MainNetParams {
    private static final Logger log = LoggerFactory.getLogger(PepepowMainNetParams.class);

    public static final boolean ENABLE_LLMQ = false;

    public PepepowMainNetParams() {
        super();
        id = "org.pepepow.mainnet";

        // Ensure LLMQ is disabled
        llmqChainLocks = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqForInstantSend = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqTypePlatform = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqTypeDIP0024InstantSend = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqTypeMnhf = LLMQParameters.LLMQType.LLMQ_NONE;
        llmqTypeAssetLocks = LLMQParameters.LLMQType.LLMQ_NONE;

        // PEPEPOW Difficulty parameters
        // 0x1e0fffffL is the standard "highest possible target" (lowest difficulty) for
        // many coins (like Bitcoin/Dash)
        maxTarget = Utils.decodeCompactBits(0x1e0fffffL);

        // Initialize other new fields to safe defaults to avoid NPE
        maxTargetAfterSwitch = maxTarget;
        newHashHeight = Integer.MAX_VALUE; // effectively disabled or future
        newHashBits = 0x1e0fffffL;
    }

    private static PepepowMainNetParams instance;

    public static synchronized PepepowMainNetParams get() {
        if (instance == null) {
            instance = new PepepowMainNetParams();
        }
        return instance;
    }

    @Override
    public boolean isLlmqEnabled() {
        return ENABLE_LLMQ;
    }

    @Override
    public String getPaymentProtocolId() {
        return PAYMENT_PROTOCOL_ID_MAINNET;
    }
}
