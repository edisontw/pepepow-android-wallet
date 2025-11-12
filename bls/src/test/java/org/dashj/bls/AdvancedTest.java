/**
 * Copyright (c) 2022-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package org.dashj.bls;

import org.dashj.bls.Utils.ByteVector;
import org.dashj.bls.Utils.ByteVectorList;
import org.dashj.bls.Utils.G1ElementList;
import org.dashj.bls.Utils.G2ElementList;
import org.dashj.bls.Utils.PrivateKeyList;
import org.dashj.bls.Utils.HexUtils;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class AdvancedTest extends BaseTest {

    @Test
    public void shouldAggregateWithMultipleLevelsDegenerate() {
        byte [] message1 = new byte[]{100, 2, (byte)254, 88, 90, 45, 23};
        PrivateKey sk1 = new AugSchemeMPL().keyGen(Entropy.getRandomSeed(32));
        G1Element pk1 = sk1.getG1Element();
        G2Element aggSig = new AugSchemeMPL().sign(sk1, message1);
        G1ElementVector pks = new G1ElementVector(new G1Element[]{pk1});
        Uint8VectorVector ms = new ByteVectorList(message1);

        for (int i = 0; i < 10; i++) {
            PrivateKey sk = new AugSchemeMPL().keyGen(Entropy.getRandomSeed(32));
            G1Element pk = sk.getG1Element();
            pks.add(pk);
            ms.add(new ByteVector(message1));
            G2Element sig = new AugSchemeMPL().sign(sk, message1);
            aggSig = new AugSchemeMPL().aggregate(new G2ElementVector(new G2Element[]{aggSig, sig}));
        }
        assertTrue(new AugSchemeMPL().aggregateVerify(pks, ms, aggSig));
    }
    
    @Test
    public void shouldaggregateWithMultipleLevelsDifferentMessages() {
        byte [] message1 = new byte[]{100, 2, (byte)254, 88, 90, 45, 23};
        byte [] message2 = new byte[]{(byte)192, 29, 2, 0, 0, 45, 23};
        byte [] message3 = new byte[]{52, 29, 2, 0, 0, 45, 102};
        byte [] message4 = new byte[]{99, 29, 2, 0, 0, 45, (byte)222};

        PrivateKey sk1 = new AugSchemeMPL().keyGen(Entropy.getRandomSeed(32));
        PrivateKey sk2 = new AugSchemeMPL().keyGen(Entropy.getRandomSeed(32));

        G1Element pk1 = sk1.getG1Element();
        G1Element pk2 = sk2.getG1Element();

        G2Element sig1 = new AugSchemeMPL().sign(sk1, message1);
        G2Element sig2 = new AugSchemeMPL().sign(sk2, message2);
        G2Element sig3 = new AugSchemeMPL().sign(sk2, message3);
        G2Element sig4 = new AugSchemeMPL().sign(sk1, message4);

        G2ElementVector sigsL = new G2ElementVector(new G2Element[]{sig1, sig2});
        G1ElementVector pksL = new G1ElementVector(new G1Element[]{pk1, pk2});
        Uint8VectorVector messagesL = new ByteVectorList(message1, message2);
        G2Element aggSigL = new AugSchemeMPL().aggregate(sigsL);

        G2ElementVector sigsR = new G2ElementVector(new G2Element[]{sig3, sig4});
        G1ElementVector pksR = new G1ElementVector(new G1Element[]{pk2, pk1});
        G2Element aggSigR = new AugSchemeMPL().aggregate(sigsR);

        G2ElementVector sigs = new G2ElementVector(new G2Element[]{aggSigL, aggSigR});
        G2Element aggSig = new AugSchemeMPL().aggregate(sigs);

        G1ElementVector allPks = new G1ElementVector(new G1Element[]{pk1, pk2, pk2, pk1});
        Uint8VectorVector allMessages = new ByteVectorList(message1, message2, message3, message4);
        assertTrue(new AugSchemeMPL().aggregateVerify(allPks, allMessages, aggSig));
    }
    
    @Test
    public void readmeTest() {
        // Example seed, used to generate private key. Always use
        // a secure RNG with sufficient entropy to generate a seed (at least 32 bytes).
        byte [] seed = {0,  50, 6, (byte)244, 24,  (byte)199, 1,  25,  52,  88,  (byte)192,
                19, 18, 12, 89,  6,   (byte)220, 18, 102, 58,  (byte)209, 82,
                12, 62, 89, 110, (byte)182, 9,   44, 20,  (byte)254, 22};

        PrivateKey sk = new AugSchemeMPL().keyGen(seed);
        G1Element pk = sk.getG1Element();

        byte [] message = {1, 2, 3, 4, 5};  // Message is passed in as a byte vector
        G2Element signature = new AugSchemeMPL().sign(sk, message);

        byte [] skBytes = sk.serialize();
        byte [] pkBytes = pk.serialize();
        byte [] signatureBytes = signature.serialize();

        System.out.println(HexUtils.hexStr(skBytes));    // 32 bytes
        System.out.println(HexUtils.hexStr(pkBytes));    // 48 bytes
        System.out.println(HexUtils.hexStr(signatureBytes));  // 96 bytes

        // Takes array of 32 bytes
        PrivateKey skc = PrivateKey.fromBytes(skBytes);

        // Takes array of 48 bytes
        pk = G1Element.fromBytes(pkBytes);

        // Takes array of 96 bytes
        signature = G2Element.fromBytes(signatureBytes);

        assertTrue(new AugSchemeMPL().verify(pk, message, signature));

        // Generate some more private keys
        seed[0] = 1;
        PrivateKey sk1 = new AugSchemeMPL().keyGen(seed);
        seed[0] = 2;
        PrivateKey sk2 = new AugSchemeMPL().keyGen(seed);
        byte [] message2 = {1, 2, 3, 4, 5, 6, 7};

        // Generate first sig
        G1Element pk1 = sk1.getG1Element();
        G2Element sig1 = new AugSchemeMPL().sign(sk1, message);

        // Generate second sig
        G1Element pk2 = sk2.getG1Element();
        G2Element sig2 = new AugSchemeMPL().sign(sk2, message2);

        // Signatures can be noninteractively combined by anyone
        G2Element aggSig = new AugSchemeMPL().aggregate(new G2ElementList(sig1, sig2));

        assertTrue(new AugSchemeMPL().aggregateVerify(new G1ElementList(pk1, pk2), new ByteVectorList(message, message2), aggSig));

        seed[0] = 3;
        PrivateKey sk3 = new AugSchemeMPL().keyGen(seed);
        G1Element pk3 = sk3.getG1Element();
        byte [] message3 = new byte[]{100, 2, (byte)254, 88, 90, 45, 23};
        G2Element sig3 = new AugSchemeMPL().sign(sk3, message3);


        // Arbitrary trees of aggregates
        G2Element aggSigFinal = new AugSchemeMPL().aggregate(new G2ElementList(aggSig, sig3));

        assertTrue(new AugSchemeMPL().aggregateVerify(new G1ElementList(pk1, pk2, pk3), new ByteVectorList(message, message2, message3), aggSigFinal));

        // If the same message is signed, you can use Proof of Possession (PopScheme) for efficiency
        // A proof of possession MUST be passed around with the PK to ensure security.

        G2Element popSig1 = new PopSchemeMPL().sign(sk1, message);
        G2Element popSig2 = new PopSchemeMPL().sign(sk2, message);
        G2Element popSig3 = new PopSchemeMPL().sign(sk3, message);
        G2Element pop1 = new PopSchemeMPL().popProve(sk1);
        G2Element pop2 = new PopSchemeMPL().popProve(sk2);
        G2Element pop3 = new PopSchemeMPL().popProve(sk3);

        assertTrue(new PopSchemeMPL().popVerify(pk1, pop1));
        assertTrue(new PopSchemeMPL().popVerify(pk2, pop2));
        assertTrue(new PopSchemeMPL().popVerify(pk3, pop3));
        G2Element popSigAgg = new PopSchemeMPL().aggregate(new G2ElementList(popSig1, popSig2, popSig3));

        assertTrue(new PopSchemeMPL().fastAggregateVerify(new G1ElementList(pk1, pk2, pk3), message, popSigAgg));

        // Aggregate public key, indistinguishable from a single public key
        G1Element popAggPk = DASHJBLS.add(DASHJBLS.add(pk1, pk2), pk3);
        assertTrue(new PopSchemeMPL().verify(popAggPk, message, popSigAgg));

        // Aggregate private keys
        PrivateKey aggSk = PrivateKey.aggregate(new PrivateKeyList(sk1, sk2, sk3));
        assertObjectEquals(new PopSchemeMPL().sign(aggSk, message), popSigAgg);


        PrivateKey masterSk = new AugSchemeMPL().keyGen(seed);
        PrivateKey child = new AugSchemeMPL().deriveChildSk(masterSk, 152);
        PrivateKey grandchild = new AugSchemeMPL().deriveChildSk(child, 952);

        G1Element masterPk = masterSk.getG1Element();
        PrivateKey childU = new AugSchemeMPL().deriveChildSkUnhardened(masterSk, 22);
        PrivateKey grandchildU = new AugSchemeMPL().deriveChildSkUnhardened(childU, 0);

        G1Element childUPk = new AugSchemeMPL().deriveChildPkUnhardened(masterPk, 22);
        G1Element grandchildUPk = new AugSchemeMPL().deriveChildPkUnhardened(childUPk, 0);

        assertObjectEquals(grandchildUPk, grandchildU.getG1Element());
    }
}
