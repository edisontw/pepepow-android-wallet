package org.dashj.bls;

import com.google.common.collect.Lists;
import org.dashj.bls.Utils.ByteVector;
import org.dashj.bls.Utils.ByteVectorList;
import org.dashj.bls.Utils.G1ElementList;
import org.dashj.bls.Utils.G2ElementList;
import org.dashj.bls.Utils.PrivateKeyList;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UtilityClassTest extends BaseTest {
    @Test
    public void privateKeyListTest() {
        PrivateKey sk = PrivateKey.fromSeedBIP32(Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE));
        PrivateKey skTwo = PrivateKey.fromSeedBIP32(Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE));
        PrivateKey skThree = PrivateKey.fromSeedBIP32(Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE));
        PrivateKey [] skArrayAll = new PrivateKey[] { sk, skTwo, skThree};

        PrivateKeyList emptyList = new PrivateKeyList();
        assertTrue(emptyList.isEmpty());

        PrivateKeyList listOfOne = new PrivateKeyList(sk);
        assertEquals(1, listOfOne.size());
        PrivateKeyList listOfMany = new PrivateKeyList(sk, skTwo, skThree);
        assertEquals(3, listOfMany.size());
        PrivateKeyList copy = new PrivateKeyList(listOfMany);
        assertEquals(3, copy.size());
        PrivateKeyList fromArrayAll = new PrivateKeyList(skArrayAll);
        PrivateKeyVector copyTwo = new PrivateKeyVector(listOfMany);

        for (int i = 0; i < copy.size(); ++i) {
            assertObjectEquals(copy.get(i), listOfMany.get(i));
            assertObjectEquals(copy.get(i), fromArrayAll.get(i));
            assertObjectEquals(copyTwo.get(i), listOfMany.get(i));
        }

        //remove the last to elements of copy
        copy.remove(2);
        copy.remove(1);
        for (int i = 0; i < copy.size(); ++i) {
            assertObjectEquals(copy.get(i), listOfOne.get(i));
        }

        copy.add(1, skTwo);
        copy.add(2, skThree);
        assertEquals(3, copy.size());
        copy.set(1, skThree);
        copy.set(2, skTwo);
        assertObjectEquals(copy.get(2), skTwo);
        copy.removeRange(1, 3);
        assertEquals(1, copy.size());


        copy.clear();
        assertTrue(copy.isEmpty());

        assertTrue(listOfMany.capacity() >= listOfMany.size());
    }

    @Test
    public void g1ElementListTest() {
        G1Element pk = PrivateKey.fromSeedBIP32(Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE)).getG1Element();
        G1Element pkTwo = PrivateKey.fromSeedBIP32(Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE)).getG1Element();
        G1Element pkThree = PrivateKey.fromSeedBIP32(Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE)).getG1Element();
        G1Element [] pkArrayAll = new G1Element[] { pk, pkTwo, pkThree };

        G1ElementList emptyList = new G1ElementList();
        assertTrue(emptyList.isEmpty());

        G1ElementList listOfOne = new G1ElementList(pk);
        assertEquals(1, listOfOne.size());
        G1ElementList listOfMany = new G1ElementList(pk, pkTwo, pkThree);
        assertEquals(3, listOfMany.size());
        G1ElementList copy = new G1ElementList(listOfMany);
        assertEquals(3, copy.size());
        G1ElementList fromArrayAll = new G1ElementList(pkArrayAll);
        G1ElementVector copyTwo = new G1ElementVector(listOfMany);

        for (int i = 0; i < copy.size(); ++i) {
            assertObjectEquals(copy.get(i), listOfMany.get(i));
            assertObjectEquals(copy.get(i), fromArrayAll.get(i));
            assertObjectEquals(copyTwo.get(i), listOfMany.get(i));
        }

        //remove the last to elements of copy
        copy.remove(2);
        copy.remove(1);
        for (int i = 0; i < copy.size(); ++i) {
            assertObjectEquals(copy.get(i), listOfOne.get(i));
        }

        copy.add(1, pkTwo);
        copy.add(2, pkThree);
        assertEquals(3, copy.size());
        copy.set(1, pkThree);
        copy.set(2, pkTwo);
        assertObjectEquals(copy.get(2), pkTwo);
        copy.removeRange(1, 3);
        assertEquals(1, copy.size());


        copy.clear();
        assertTrue(copy.isEmpty());

        assertTrue(listOfMany.capacity() >= listOfMany.size());
    }

    @Test
    public void g2ElementListTest() {
        PrivateKey sk = PrivateKey.fromSeedBIP32(Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE));
        PrivateKey skTwo = PrivateKey.fromSeedBIP32(Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE));
        PrivateKey skThree = PrivateKey.fromSeedBIP32(Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE));

        byte [] message = Entropy.getRandomSeed(32);

        G2Element sig = new BasicSchemeMPL().sign(sk, message);
        G2Element sigTwo = new BasicSchemeMPL().sign(skTwo, message);
        G2Element sigThree = new BasicSchemeMPL().sign(skThree, message);
        G2Element [] sigArrayAll = new G2Element[] { sig, sigTwo, sigThree };

        G2ElementList emptyList = new G2ElementList();
        assertTrue(emptyList.isEmpty());

        G2ElementList listOfOne = new G2ElementList(sig);
        assertEquals(1, listOfOne.size());
        G2ElementList listOfMany = new G2ElementList(sig, sigTwo, sigThree);
        assertEquals(3, listOfMany.size());
        G2ElementList copy = new G2ElementList(listOfMany);
        assertEquals(3, copy.size());
        G2ElementList fromArrayAll = new G2ElementList(sigArrayAll);
        G2ElementVector copyTwo = new G2ElementVector(listOfMany);

        for (int i = 0; i < copy.size(); ++i) {
            assertObjectEquals(copy.get(i), listOfMany.get(i));
            assertObjectEquals(copy.get(i), fromArrayAll.get(i));
            assertObjectEquals(copyTwo.get(i), listOfMany.get(i));
        }

        //remove the last to elements of copy
        copy.remove(2);
        copy.remove(1);
        for (int i = 0; i < copy.size(); ++i) {
            assertObjectEquals(copy.get(i), listOfOne.get(i));
        }

        copy.add(1, sigTwo);
        copy.add(2, sigThree);
        assertEquals(3, copy.size());
        copy.set(1, sigThree);
        copy.set(2, sigTwo);
        assertObjectEquals(copy.get(2), sigTwo);
        copy.removeRange(1, 3);
        assertEquals(1, copy.size());


        copy.clear();
        assertTrue(copy.isEmpty());

        assertTrue(listOfMany.capacity() >= listOfMany.size());
    }

    @Test
    public void uint8VectorVectorTest() {
        byte[]bv = Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE);
        byte[] bvTwo = Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE);
        byte[] bvThree = Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE);
        byte[][] pkArrayAll = new byte[][] { bv, bvTwo, bvThree };
        List<byte[]> pkList = Arrays.asList(pkArrayAll);

        ByteVectorList emptyList = new ByteVectorList();
        assertTrue(emptyList.isEmpty());

        ByteVectorList listOfOne = new ByteVectorList(bv);
        assertEquals(1, listOfOne.size());
        ByteVectorList listOfMany = new ByteVectorList(bv, bvTwo, bvThree);
        assertEquals(3, listOfMany.size());
        ByteVectorList copy = new ByteVectorList(listOfMany);
        assertEquals(3, copy.size());
        ByteVectorList fromArrayAll = new ByteVectorList(pkArrayAll);
        ByteVectorList copyTwo = new ByteVectorList(listOfMany);
        ByteVectorList copyThree = new ByteVectorList(pkList);

        for (int i = 0; i < copy.size(); ++i) {
            assertEquals(copy.get(i), listOfMany.get(i));
            assertEquals(copy.get(i), fromArrayAll.get(i));
            assertEquals(copyTwo.get(i), listOfMany.get(i));
            assertEquals(copyThree.get(i), listOfMany.get(i));
        }

        //remove the last to elements of copy
        copy.remove(2);
        copy.remove(1);
        for (int i = 0; i < copy.size(); ++i) {
            assertEquals(copy.get(i), listOfOne.get(i));
        }

        copy.add(1, new ByteVector(bvTwo));
        copy.add(2, new ByteVector(bvThree));
        assertEquals(3, copy.size());
        copy.set(1, new ByteVector(bvThree));
        copy.set(2, new ByteVector(bvTwo));
        assertEquals(copy.get(2), new ByteVector(bvTwo));
        copy.removeRange(1, 3);
        assertEquals(1, copy.size());


        copy.clear();
        assertTrue(copy.isEmpty());

        assertTrue(listOfMany.capacity() >= listOfMany.size());
    }

    @Test
    public void uint8VectorTest() {
        byte[] message = Entropy.getRandomSeed(32);
        short[] messageTwo = new short[] { 1, 2, 3, 4, 5, 9, 8, 7, 6};
        short[] messageThree = new short[] { 1, 4, 5, 9, 8, 7, 6 };
        ArrayList<Short> messageFour = Lists.newArrayList();
        for (short element: messageTwo) {
            messageFour.add(element);
        }
        short[] messageFive = new short[] { 1, 1, 1, 1, 1};

        ByteVector empty = new ByteVector();
        assertTrue(empty.isEmpty());

        ByteVector msgOne = new ByteVector(message);
        assertEquals(message.length, msgOne.size());
        ByteVector msgTwo = new ByteVector(messageTwo);
        assertEquals(messageTwo.length, msgTwo.size());
        ByteVector msgFive = new ByteVector(5, (short) 1);

        ByteVector copy = new ByteVector(msgOne);
        assertEquals(message.length, copy.size());
        ByteVector copyTwo = new ByteVector(msgTwo);
        ByteVector copyThree = new ByteVector(copyTwo);
        ByteVector copyFour = new ByteVector(messageFour);

        for (int i = 0; i < copyTwo.size(); ++i) {
            assertEquals(copyTwo.get(i), msgTwo.get(i));
            assertEquals((short)copyTwo.get(i), messageTwo[i]);
        }

        assertEquals(messageTwo.length, copyTwo.size());
        assertEquals(copyThree, copyTwo);
        assertEquals(copyFour, copyTwo);
        for (int i = 0; i < msgFive.size(); ++i) {
            assertEquals((short) msgFive.get(i), messageFive[i]);
        }

        //remove the last to elements of copy
        copyTwo.remove(1);
        copyTwo.remove(1);
        for (int i = 0; i < copyTwo.size(); ++i) {
            assertEquals((short)copyTwo.get(i), messageThree[i]);
        }
        assertEquals(copyTwo, new ByteVector(messageThree));

        copyTwo.add(1, (short)2);
        copyTwo.add(2, (short)3);
        assertEquals(messageTwo.length, copyTwo.size());
        copyTwo.set(1, (short) 3);
        copyTwo.set(2, (short) 2);
        assertEquals((short)copyTwo.get(2), (short)2);
        copyTwo.removeRange(1, 3);
        assertEquals(messageTwo.length - 2, copyTwo.size());


        copy.clear();
        assertTrue(copy.isEmpty());

        assertTrue(copyTwo.capacity() >= copyTwo.size());
    }
}
