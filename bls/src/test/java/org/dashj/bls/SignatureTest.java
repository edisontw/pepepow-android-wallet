/**
 * Copyright (c) 2022-present, Dash Core Group
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package org.dashj.bls;

import org.dashj.bls.Utils.ByteVector;
import org.dashj.bls.Utils.ByteVectorList;
import org.dashj.bls.Utils.G1ElementList;
import org.dashj.bls.Utils.G2ElementList;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SignatureTest extends BaseTest {

    @Test
    public void shouldUseCopyConstructorTest() {
        byte[] message1 = {1, 65, (byte) 254, 88, 90, 45, 22};

        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x30);
        PrivateKey sk1 = new BasicSchemeMPL().keyGen(seed);
        G1Element pk1 = sk1.getG1Element();
        PrivateKey sk2 = new PrivateKey(sk1);

        byte[] skBytes = new byte[PrivateKey.PRIVATE_KEY_SIZE];
        sk2.serialize(skBytes);
        PrivateKey sk4 = PrivateKey.fromBytes(skBytes);

        G1Element pk2 = new G1Element(pk1);
        G2Element sig1 = new BasicSchemeMPL().sign(sk4, message1);
        G2Element sig2 = new G2Element(sig1);

        assertTrue(new BasicSchemeMPL().verify(pk2, message1, sig2));
    }

    @Test
    public void shouldSignWithZeroKey() {
        byte[] sk0 = new byte[32];
        PrivateKey sk = PrivateKey.fromBytes(sk0);
        assertObjectEquals(sk.getG1Element(), new G1Element());  // Infinity
        assertObjectEquals(sk.getG2Element(), new G2Element());  // Infinity
        assertObjectEquals(new BasicSchemeMPL().sign(sk, new byte[]{1, 2, 3}), new G2Element());
        assertObjectEquals(new AugSchemeMPL().sign(sk, new byte[]{1, 2, 3}), new G2Element());
        assertObjectEquals(new PopSchemeMPL().sign(sk, new byte[]{1, 2, 3}), new G2Element());
    }

    @Test
    public void shouldUseEqualityOperators() {
        byte[] message1 = {1, 65, (byte) 254, 88, 90, 45, 22};
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x40);
        byte[] seed3 = new byte[32];
        Arrays.fill(seed3, (byte) 0x50);

        PrivateKey sk1 = new BasicSchemeMPL().keyGen(seed);
        PrivateKey sk2 = new PrivateKey(sk1);
        PrivateKey sk3 = new BasicSchemeMPL().keyGen(seed3);
        G1Element pk1 = sk1.getG1Element();
        G1Element pk2 = sk2.getG1Element();
        G1Element pk3 = new G1Element(pk2);
        G1Element pk4 = sk3.getG1Element();
        G2Element sig1 = new BasicSchemeMPL().sign(sk1, message1);
        G2Element sig2 = new BasicSchemeMPL().sign(sk1, message1);
        G2Element sig3 = new BasicSchemeMPL().sign(sk2, message1);
        G2Element sig4 = new BasicSchemeMPL().sign(sk3, message1);

        assertObjectEquals(sk1, sk2);
        assertObjectNotEquals(sk1, sk3);
        assertObjectEquals(pk1, pk2);
        assertObjectEquals(pk2, pk3);
        assertObjectNotEquals(pk1, pk4);
        assertObjectEquals(sig1, sig2);
        assertObjectEquals(sig2, sig3);
        assertObjectNotEquals(sig3, sig4);

        assertArrayEquals(pk1.serialize(), pk2.serialize());
        assertArrayEquals(sig1.serialize(), sig2.serialize());
    }

    @Test
    public void shouldSerializeAndDeserialize() {
        byte[] message1 = {1, 65, (byte) 254, 88, 90, 45, 22};

        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x40);
        PrivateKey sk1 = new BasicSchemeMPL().keyGen(seed);
        G1Element pk1 = sk1.getG1Element();

        byte[] skData = new byte[PrivateKey.PRIVATE_KEY_SIZE];
        sk1.serialize(skData);
        PrivateKey sk2 = PrivateKey.fromBytes(skData);
        assertObjectEquals(sk1, sk2);

        byte[] pkData = pk1.serialize();

        G1Element pk2 = G1Element.fromBytes(pkData);
        assertObjectEquals(pk1, pk2);

        G2Element sig1 = new BasicSchemeMPL().sign(sk1, message1);

        byte[] sigData = sig1.serialize();

        G2Element sig2 = G2Element.fromBytes(sigData);
        assertObjectEquals(sig1, sig2);

        assertTrue(new BasicSchemeMPL().verify(pk2, message1, sig2));
    }

    @Test
    public void shouldNotVerifyAggregateWithSameMessageUnderBasicScheme() {
        byte[] message = {100, 2, (byte) 254, 88, 90, 45, 23};
        byte[] hash = new byte[BLS.MESSAGE_HASH_LEN];

        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x50);

        byte[] seed2 = new byte[32];
        Arrays.fill(seed, (byte) 0x70);

        PrivateKey sk1 = new BasicSchemeMPL().keyGen(seed);
        PrivateKey sk2 = new BasicSchemeMPL().keyGen(seed2);

        G1Element pk1 = sk1.getG1Element();
        G1Element pk2 = sk2.getG1Element();

        G2Element sig1 = new BasicSchemeMPL().sign(sk1, message);
        G2Element sig2 = new BasicSchemeMPL().sign(sk2, message);

        G2Element aggSig = new BasicSchemeMPL().aggregate(new G2ElementList(sig1, sig2));
        assertFalse(new BasicSchemeMPL().aggregateVerify(new G1ElementList(pk1, pk2), new ByteVectorList(message, message), aggSig));
    }

    @Test
    public void shouldVerifyAggregateWithSameMessageUnderAugSchemePopScheme() {
        byte[] message = {100, 2, (byte) 254, 88, 90, 45, 23};
        byte[] hash = new byte[BLS.MESSAGE_HASH_LEN];

        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x50);

        byte[] seed2 = new byte[32];
        Arrays.fill(seed, (byte) 0x70);

        PrivateKey sk1 = new BasicSchemeMPL().keyGen(seed);
        PrivateKey sk2 = new BasicSchemeMPL().keyGen(seed2);

        G1Element pk1 = sk1.getG1Element();
        G1Element pk2 = sk2.getG1Element();

        G2Element sig1Aug = new AugSchemeMPL().sign(sk1, message);
        G2Element sig2Aug = new AugSchemeMPL().sign(sk2, message);
        G2Element aggSigAug = new AugSchemeMPL().aggregate(new G2ElementList(sig1Aug, sig2Aug));
        assertTrue(new AugSchemeMPL().aggregateVerify(new G1ElementList(pk1, pk2), new ByteVectorList(message, message), aggSigAug));

        G2Element sig1Pop = new PopSchemeMPL().sign(sk1, message);
        G2Element sig2Pop = new PopSchemeMPL().sign(sk2, message);
        G2Element aggSigPop = new PopSchemeMPL().aggregate(new G2ElementList(sig1Pop, sig2Pop));
        assertTrue(new PopSchemeMPL().aggregateVerify(new G1ElementList(pk1, pk2), new ByteVectorList(message, message), aggSigPop));
    }

    @Test
    public void shouldAugAggregateManyG2ElementsDiffMessage() {
        G1ElementVector pks = new G1ElementVector();
        G2ElementVector sigs = new G2ElementVector();
        ByteVectorList ms = new ByteVectorList();

        for (int i = 0; i < 80; i++) {
            byte[] message = {0, 100, 2, 45, 64, 12, 12, 63, (byte) i};
            PrivateKey sk = new BasicSchemeMPL().keyGen(Entropy.getRandomSeed(32));
            pks.add(sk.getG1Element());
            G2Element sig = new AugSchemeMPL().sign(sk, message);
            sigs.add(sig);
            ms.add(new ByteVector(message));
        }

        G2Element aggSig = new AugSchemeMPL().aggregate(sigs);

        assertTrue(new AugSchemeMPL().aggregateVerify(pks, ms, aggSig));
    }

    @Test
    public void aggregateVerificationOfZeroItemsWithInfinity() {
        G1ElementVector pks_as_g1 = new G1ElementVector();
        ByteVectorList pks_as_bytes = new ByteVectorList();
        ByteVectorList msgs = new ByteVectorList();
        G2ElementVector sigs = new G2ElementVector();

        sigs.add(new G2Element());
        G2Element aggSig = new AugSchemeMPL().aggregate(sigs);

        assertTrue(aggSig.serialize().length != 0);
        assertObjectEquals(aggSig, new G2Element());

        assertTrue(new AugSchemeMPL().aggregateVerify(pks_as_g1, msgs, aggSig));
        assertTrue(new AugSchemeMPL().aggregateVerify(pks_as_bytes, msgs, aggSig.serialize()));

        assertTrue(new BasicSchemeMPL().aggregateVerify(pks_as_g1, msgs, aggSig));
        assertTrue(new BasicSchemeMPL().aggregateVerify(pks_as_bytes, msgs, aggSig.serialize()));

        // FastAggregateVerify takes one message, and requires at least one key
        byte[] msg = new byte[0];
        assertEquals(0, pks_as_g1.size());
        assertFalse(new PopSchemeMPL().fastAggregateVerify(pks_as_g1, msg, aggSig));
        assertEquals(0, pks_as_bytes.size());
        assertFalse(new PopSchemeMPL().fastAggregateVerify(pks_as_bytes, msg, aggSig.serialize()));
    }
}
