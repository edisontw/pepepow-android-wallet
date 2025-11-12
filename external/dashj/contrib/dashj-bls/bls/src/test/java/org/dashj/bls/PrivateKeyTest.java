/**
 * Copyright (c) 2022-present, Dash Core Group
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package org.dashj.bls;

import org.dashj.bls.Utils.HexUtils;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PrivateKeyTest extends BaseTest {
    byte[] buffer;

    @Before
    public void beforeEach() {
        buffer = Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE);
    }

    @Test
    public void copyConstructorAssignmentOperator() {
        PrivateKey pk1 = PrivateKey.randomPrivateKey();
        PrivateKey pk2 = PrivateKey.randomPrivateKey();
        PrivateKey pk3 = new PrivateKey(pk2);
        assertFalse(pk1.isZero());
        assertFalse(pk2.isZero());
        assertFalse(pk3.isZero());
        assertNotEquals(pk1, pk2);
        assertObjectEquals(pk3, pk2);
        assertTrue(pk2.getG1Element().isValid()); // cache previous g1
        assertTrue(pk2.getG2Element().isValid()); // cache previous g2
        pk2 = pk1;
        assertEquals(pk1, pk2);
        assertObjectEquals(pk1.getG1Element(), pk2.getG1Element());
        assertObjectEquals(pk1.getG2Element(), pk2.getG2Element());
        assertNotEquals(pk3, pk2);
    }

    @Test
    public void multiplicationOperatorTest() {
        PrivateKey pk1 = PrivateKey.randomPrivateKey();
        PrivateKey pk2 = PrivateKey.randomPrivateKey();

        G1Element publicKey1 = pk1.getG1Element();
        G1Element publicKey2 = pk2.getG1Element();

        G1Element publicKeyFirst1 = DASHJBLS.multiply(publicKey1, pk2);
        G1Element privateKeyFirst1 = DASHJBLS.multiply(pk2, publicKey1);
        G1Element publicKeyFirst2 = DASHJBLS.multiply(publicKey2, pk1);
        G1Element privateKeyFirst2 = DASHJBLS.multiply(pk1, publicKey2);


        assertObjectEquals(publicKeyFirst1, privateKeyFirst1);
        assertObjectEquals(publicKeyFirst2, privateKeyFirst2);
        assertObjectEquals(publicKeyFirst1, privateKeyFirst2);
    }

    @Test
    public void equalityOperators() {
        PrivateKey pk1 = PrivateKey.randomPrivateKey();
        PrivateKey pk2 = PrivateKey.randomPrivateKey();
        PrivateKey pk3 = new PrivateKey(pk2);
        //pk3.assign(pk2);
        assertObjectNotEquals(pk1, pk2);
        assertObjectNotEquals(pk1, pk3);
        assertObjectEquals(pk2, pk3);
    }

    @Test
    public void de_serialization() {
        PrivateKey pk1 = PrivateKey.randomPrivateKey();
        pk1.serialize(buffer);
        assertArrayEquals(buffer, pk1.serialize());
        PrivateKey pk2 = PrivateKey.fromBytes(buffer, true);
        assertObjectEquals(pk1, pk2);
        byte[] shortBuffer = new byte[buffer.length - 1];
        System.arraycopy(shortBuffer, 0, buffer, 0, buffer.length - 1);
        byte[] longBuffer = new byte[buffer.length + 1];
        System.arraycopy(longBuffer, 0, buffer, 0, buffer.length);
        assertThrows(IllegalArgumentException.class, () -> PrivateKey.fromBytes(shortBuffer, true));
        assertThrows(IllegalArgumentException.class, () -> PrivateKey.fromBytes(longBuffer, true));
        PrivateKey pk3 = PrivateKey.fromBytes(buffer, true);

        byte[] bytes_ = pk3.serialize();
        PrivateKey.fromBytes(bytes_);
    }

    @Test
    public void BIP32Seed() {
        byte[] aliceSeed = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        PrivateKey pk1 = PrivateKey.fromSeedBIP32(aliceSeed);
        assertTrue(pk1.hasKeyData());
        byte[] privateKey = pk1.serialize(true);
        byte[] knownPrivateKey = HexUtils.hexToBytes("46891c2cec49593c81921e473db7480029e0fc1eb933c6b93d81f5370eb19fbd");
        assertArrayEquals(privateKey, knownPrivateKey);
        G1Element pubKey1 = pk1.getG1Element();
        byte[] pubKey1Bytes = pubKey1.serialize(true);
        byte[] knownPublicKey = HexUtils.hexToBytes("1790635de8740e9a6a6b15fb6b72f3a16afa0973d971979b6ba54761d6e2502c50db76f4d26143f05459a42cfd520d44");
        assertArrayEquals(pubKey1Bytes, knownPublicKey);
    }

    @Test
    public void serializedOrNot() {
        PrivateKey pk1 = PrivateKey.randomPrivateKey();
        byte[] serializedPrivateLegacy = pk1.serialize(true);
        byte[] serializedPrivate = pk1.serialize(false);

        G1Element g1Element = pk1.getG1Element();
        byte[] serializedPublicLegacy = g1Element.serialize(true);
        byte[] serializedPublic = g1Element.serialize(false);
    }
}

