/**
 * Copyright (c) 2022-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package org.dashj.bls;

import org.dashj.bls.Utils.ByteVectorList;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AggregateTest extends BaseTest {
    @Test
    public void shouldCreateAggregatesWithAggSk_basic_scheme() {
        final byte [] message = new byte[]{100, 2, (byte)254, 88, 90, 45, 23};
        final byte [] seed = new byte[32];
        Arrays.fill(seed, (byte)0x07);
        final byte [] seed2 = new byte[32];
        Arrays.fill(seed2, (byte)0x08);

        final PrivateKey sk1 = new BasicSchemeMPL().keyGen(seed);
        final G1Element pk1 = sk1.getG1Element();

        final PrivateKey sk2 = new BasicSchemeMPL().keyGen(seed2);
        final G1Element pk2 = sk2.getG1Element();

        final PrivateKey aggSk = PrivateKey.aggregate(new PrivateKeyVector(new PrivateKey[]{sk1, sk2}));
        final PrivateKey aggSkAlt = PrivateKey.aggregate(new PrivateKeyVector(new PrivateKey[]{sk2, sk1}));
        assertObjectEquals(aggSk, aggSkAlt);

        final G1Element aggPubKey = DASHJBLS.add(pk1,pk2);
        assertObjectEquals(aggPubKey, aggSk.getG1Element());

        final G2Element sig1 = new BasicSchemeMPL().sign(sk1, message);
        final G2Element sig2 = new BasicSchemeMPL().sign(sk2, message);

        final G2Element aggSig2 = new BasicSchemeMPL().sign(aggSk, message);


        final G2Element aggSig = new BasicSchemeMPL().aggregate(new G2ElementVector(new G2Element[]{sig1, sig2}));
        assertObjectEquals(aggSig, aggSig2);

        // Verify as a single G2Element
        assertTrue(new BasicSchemeMPL().verify(aggPubKey, message, aggSig));
        assertTrue(new BasicSchemeMPL().verify(aggPubKey, message, aggSig2));

        // Verify aggregate with both keys (Fails since not distinct)
        assertFalse(new BasicSchemeMPL().aggregateVerify(new G1ElementVector(new G1Element[]{pk1, pk2}), new ByteVectorList(message, message), aggSig));
        assertFalse(new BasicSchemeMPL().aggregateVerify(new G1ElementVector(new G1Element[]{pk1, pk2}), new ByteVectorList(message, message), aggSig2));

        // Try the same with distinct message, and same sk
        byte [] message2 = new byte[]{(byte)200, 29, 54, 8, 9, 29, (byte)155, 55};
        G2Element sig3 = new BasicSchemeMPL().sign(sk2, message2);
        G2Element aggSigFinal = new BasicSchemeMPL().aggregate(new G2ElementVector(new G2Element[]{aggSig, sig3}));
        G2Element aggSigAlt = new BasicSchemeMPL().aggregate(new G2ElementVector(new G2Element[]{sig1, sig2, sig3}));
        G2Element aggSigAlt2 = new BasicSchemeMPL().aggregate(new G2ElementVector(new G2Element[]{sig1, sig3, sig2}));
        assertObjectEquals(aggSigFinal, aggSigAlt);
        assertObjectEquals(aggSigFinal, aggSigAlt2);

        PrivateKey skFinal = PrivateKey.aggregate(new PrivateKeyVector(new PrivateKey[]{aggSk, sk2}));
        PrivateKey skFinalAlt = PrivateKey.aggregate(new PrivateKeyVector(new PrivateKey[]{sk2, sk1, sk2}));
        assertObjectEquals(skFinal, skFinalAlt);
        assertObjectNotEquals(skFinal, aggSk);

        G1Element pkFinal = DASHJBLS.add(aggPubKey, pk2);
        G1Element pkFinalAlt = DASHJBLS.add(DASHJBLS.add(pk2, pk1), pk2);
        assertObjectEquals(pkFinal, pkFinalAlt);
        assertObjectNotEquals(pkFinal, aggPubKey);

        // Cannot verify with aggPubKey (since we have multiple messages)
        assertTrue(new BasicSchemeMPL().aggregateVerify(
                new G1ElementVector(new G1Element[]{aggPubKey, pk2}),
                new ByteVectorList(message, message2),
                aggSigFinal)
        );
    }
}
