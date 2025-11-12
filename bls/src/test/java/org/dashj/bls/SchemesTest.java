/**
 * Copyright (c) 2022-present, Dash Core Group
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package org.dashj.bls;

import com.google.common.collect.Lists;
import org.dashj.bls.Utils.ByteVectorList;
import org.dashj.bls.Utils.G1ElementList;
import org.dashj.bls.Utils.G2ElementList;
import org.dashj.bls.Utils.HexUtils;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SchemesTest extends BaseTest {
    @Test
    public void basicScheme() {
        byte[] seed1 = new byte[32];
        Arrays.fill(seed1, (byte) 0x04);
        byte[] seed2 = new byte[32];
        Arrays.fill(seed2, (byte) 0x05);
        byte[] msg1 = {7, 8, 9};
        byte[] msg2 = {10, 11, 12};
        ByteVectorList msgs = new ByteVectorList(msg1, msg2);

        PrivateKey sk1 = new BasicSchemeMPL().keyGen(seed1);
        G1Element pk1 = new BasicSchemeMPL().skToG1(sk1);
        byte[] pk1v = new BasicSchemeMPL().skToPk(sk1);
        G2Element sig1 = new BasicSchemeMPL().sign(sk1, msg1);
        byte[] sig1v = new BasicSchemeMPL().sign(sk1, msg1).serialize();


        assertTrue(new BasicSchemeMPL().verify(pk1v, msg1, sig1v));

        PrivateKey sk2 = new BasicSchemeMPL().keyGen(seed2);
        G1Element pk2 = new BasicSchemeMPL().skToG1(sk2);
        byte[] pk2v = new BasicSchemeMPL().skToPk(sk2);
        G2Element sig2 = new BasicSchemeMPL().sign(sk2, msg2);
        byte[] sig2v = new BasicSchemeMPL().sign(sk2, msg2).serialize();

        // Wrong G2Element
        assertFalse(new BasicSchemeMPL().verify(pk1, msg1, sig2));
        assertFalse(new BasicSchemeMPL().verify(pk1v, msg1, sig2v));
        // Wrong msg
        assertFalse(new BasicSchemeMPL().verify(pk1, msg2, sig1));
        assertFalse(new BasicSchemeMPL().verify(pk1v, msg2, sig1v));
        // Wrong pk
        assertFalse(new BasicSchemeMPL().verify(pk2, msg1, sig1));
        assertFalse(new BasicSchemeMPL().verify(pk2v, msg1, sig1v));

        G2Element aggsig = new BasicSchemeMPL().aggregate(new G2ElementList(sig1, sig2));
        byte[] aggsigv = new BasicSchemeMPL().aggregate(new ByteVectorList(sig1v, sig2v));
        assertTrue(new BasicSchemeMPL().aggregateVerify(new G1ElementList(pk1, pk2), msgs, aggsig));
        assertTrue(new BasicSchemeMPL().aggregateVerify(new ByteVectorList(pk1v, pk2v), msgs, aggsigv));
    }

    @Test
    public void augScheme() {
        byte[] seed1 = new byte[32];
        Arrays.fill(seed1, (byte) 0x04);
        byte[] seed2 = new byte[32];
        Arrays.fill(seed2, (byte) 0x05);
        byte[] msg1 = {7, 8, 9};
        byte[] msg2 = {10, 11, 12};
        ByteVectorList msgs = new ByteVectorList(msg1, msg2);

        PrivateKey sk1 = new AugSchemeMPL().keyGen(seed1);
        G1Element pk1 = new AugSchemeMPL().skToG1(sk1);
        byte[] pk1v = new AugSchemeMPL().skToPk(sk1);
        G2Element sig1 = new AugSchemeMPL().sign(sk1, msg1);
        byte[] sig1v = new AugSchemeMPL().sign(sk1, msg1).serialize();

        assertTrue(new AugSchemeMPL().verify(pk1, msg1, sig1));
        assertTrue(new AugSchemeMPL().verify(pk1v, msg1, sig1v));

        PrivateKey sk2 = new AugSchemeMPL().keyGen(seed2);
        G1Element pk2 = new AugSchemeMPL().skToG1(sk2);
        byte[] pk2v = new AugSchemeMPL().skToPk(sk2);
        G2Element sig2 = new AugSchemeMPL().sign(sk2, msg2);
        byte[] sig2v = new AugSchemeMPL().sign(sk2, msg2).serialize();

        // Wrong G2Element
        assertFalse(new AugSchemeMPL().verify(pk1, msg1, sig2));
        assertFalse(new AugSchemeMPL().verify(pk1v, msg1, sig2v));
        // Wrong msg
        assertFalse(new AugSchemeMPL().verify(pk1, msg2, sig1));
        assertFalse(new AugSchemeMPL().verify(pk1v, msg2, sig1v));
        // Wrong pk
        assertFalse(new AugSchemeMPL().verify(pk2, msg1, sig1));
        assertFalse(new AugSchemeMPL().verify(pk2v, msg1, sig1v));

        G2Element aggsig = new AugSchemeMPL().aggregate(new G2ElementList(sig1, sig2));
        byte[] aggsigv = new AugSchemeMPL().aggregate(new ByteVectorList(sig1v, sig2v));
        assertTrue(new AugSchemeMPL().aggregateVerify(new G1ElementList(pk1, pk2), msgs, aggsig));
        assertTrue(new AugSchemeMPL().aggregateVerify(new ByteVectorList(pk1v, pk2v), msgs, aggsigv));
    }

    @Test
    public void popScheme() {
        byte[] seed1 = new byte[32];
        Arrays.fill(seed1, (byte) 0x04);
        byte[] seed2 = new byte[32];
        Arrays.fill(seed2, (byte) 0x05);
        byte[] msg1 = {7, 8, 9};
        byte[] msg2 = {10, 11, 12};
        ByteVectorList msgs = new ByteVectorList(msg1, msg2);

        PrivateKey sk1 = new PopSchemeMPL().keyGen(seed1);
        G1Element pk1 = new PopSchemeMPL().skToG1(sk1);
        byte[] pk1v = new PopSchemeMPL().skToPk(sk1);
        G2Element sig1 = new PopSchemeMPL().sign(sk1, msg1);
        byte[] sig1v = new PopSchemeMPL().sign(sk1, msg1).serialize();

        assertTrue(new PopSchemeMPL().verify(pk1, msg1, sig1));
        assertTrue(new PopSchemeMPL().verify(pk1v, msg1, sig1v));

        PrivateKey sk2 = new PopSchemeMPL().keyGen(seed2);
        G1Element pk2 = new PopSchemeMPL().skToG1(sk2);
        byte[] pk2v = new PopSchemeMPL().skToPk(sk2);
        G2Element sig2 = new PopSchemeMPL().sign(sk2, msg2);
        byte[] sig2v = new PopSchemeMPL().sign(sk2, msg2).serialize();

        // Wrong G2Element
        assertFalse(new PopSchemeMPL().verify(pk1, msg1, sig2));
        assertFalse(new PopSchemeMPL().verify(pk1v, msg1, sig2v));
        // Wrong msg
        assertFalse(new PopSchemeMPL().verify(pk1, msg2, sig1));
        assertFalse(new PopSchemeMPL().verify(pk1v, msg2, sig1v));
        // Wrong pk
        assertFalse(new PopSchemeMPL().verify(pk2, msg1, sig1));
        assertFalse(new PopSchemeMPL().verify(pk2v, msg1, sig1v));

        G2Element aggsig = new PopSchemeMPL().aggregate(new G2ElementList(sig1, sig2));
        byte[] aggsigv = new PopSchemeMPL().aggregate(new ByteVectorList(sig1v, sig2v));
        assertTrue(new PopSchemeMPL().aggregateVerify(new G1ElementList(pk1, pk2), msgs, aggsig));
        assertTrue(new PopSchemeMPL().aggregateVerify(new ByteVectorList(pk1v, pk2v), msgs, aggsigv));

        // PopVerify
        G2Element proof1 = new PopSchemeMPL().popProve(sk1);
        byte[] proof1v = new PopSchemeMPL().popProve(sk1).serialize();
        assertTrue(new PopSchemeMPL().popVerify(pk1, proof1));
        assertTrue(new PopSchemeMPL().popVerify(pk1v, proof1v));

        // FastAggregateVerify
        // We want sk2 to sign the same message
        G2Element sig2_same = new PopSchemeMPL().sign(sk2, msg1);
        byte[] sig2v_same = new PopSchemeMPL().sign(sk2, msg1).serialize();
        G2Element aggsig_same = new PopSchemeMPL().aggregate(new G2ElementList(sig1, sig2_same));
        byte[] aggsigv_same = new PopSchemeMPL().aggregate(new ByteVectorList(sig1v, sig2v_same));
        assertTrue(
                new PopSchemeMPL().fastAggregateVerify(new G1ElementList(pk1, pk2), msg1, aggsig_same));
        assertTrue(new PopSchemeMPL().fastAggregateVerify(new ByteVectorList(pk1v, pk2v), msg1, aggsigv_same));
    }

    @Test
    public void legacyScheme() {
        // Test legacy example data defined in https://gist.github.com/xdustinface/318c2c08c36ab12a2b1963caf1f7815c
        String strSignHash =
                "b6d8ee31bbd375dfd55d5fb4b02cfccc68709e64f4c5ffcd3895ceb46540311d";
        String strThresholdPublicKey =
                "97a12b918eac73718d55b7fca60435ec0b442d7e24b45cbd80f5cf2ea2e14c4164333fffdb00d27e309abbafacaa9c34";
        String strThresholdSignature =
                "031c536960c9c44efefa4a3334d2d1b808f46abe121cd14a1d0b6ee6b6dca85fd3087d86fe5b1327e6792be53f4ed4df19c3af3aac79c0bb9dc36fc2a30ba566de6a82cd3e4af2d6654cbe02bb52769a06c1644a4c63b3c3a6fd16e5e68e5c0b";
        List<String> vecSecretKeySharesHex = Lists.newArrayList(
                "43dd263542a8d10bc9f843b46f15c86cd87e00c8f45fe5339b30c08e3233d8e3",
                "5e7247ef1a0e671b8349e7be3f50ec88f1eafde90345f34520010e4aa9f18731",
                "34bcc40dea17bb03ec085ac46a0ea9614b3ffc4bae8b0b292f3d7c54662b00a6",
                "139f967b6f4af5dfe2bebf8085b6332efe31c2dc348d02e6b4745a0e7e56a469",
                "08442e959054d87b5de3553ef8cfd9da923241664c35c6548b5e3271a86b4a18",
                "2698613947a156639b423ad1a9fbe45863d58540d8ebd08612504bf9cf4743ea",
                "1871c9270c8344028946eee64e79d09e4915dd3b717ffc1c9aa86faff88c0475",
                "68409938427df3567e8948c1fff8610b5cf94872eb959c90a714b7ff0f339e88",
                "70d3e3ad7d22bd30e4e2ca108a3fa47f4873bda28f3b000a218339b09db6f58a",
                "39ea630e894b71d9f28fefb551611824f16d4b16d29fdea8bb3dbd857a6bc317"
        );
        List<String> vecSigSharesHex = Lists.newArrayList(
                "0888879c99852460912fd28c7a9138926c1e87fd6609fd2d3d307764e49feb85702fd8f9b3b836bc11f7ce151b769dc70b760879d26f8c33a29e24f69297f45ef028f0794e63ddb0610db7de1a608b6d6a2129ada62b845004a408f651fd44a5",
                "16efc39fa5aa979245a82334856a97ebf3027bc6be7d35df117267a3c9b1618e32477fe1b8f4a23bdba346bf2b2b35ad0b395227de76827fd32eb9952e0d97b7dba275040101131a7fc38ea381a3099c2b3205177866ee4ab3119593bb58d092",
                "8407afd2776ab9d3f9293f1519ace1a9ce8aaf07d0a6a9785ec7ae4ae47e42102c09cfb3c8655dba014d53933af6a0b41244df006575e85e333271c90fcad80410cab4962bf4bba1570770775b1282f142b526d521a38fbc14336f22dc44a683",
                "027061a8c2d631e40882ce6919d3e5f45c4c74ac32a3bce94e5661d06cdecf2d492dfab99e9a9dd8a29a90fe8f816be30178bf9277a3751882e49bb9f08437f5f2cd9dbc12c2fdcccaf7578838e87273fd2ba87f20cf00ca5fec56822f845769",
                "178ece91967145b1dfc02de703dbd8049b05d626f18a71303ea0c23ee3b60a52bd61cc30fea3e4a562b20c20e0439a2f108b4e6b8a646f647afb64e3b355eba382380ef2c634f3a56de066b7a764249aba1c42c49d76d65e094e890cbbf005a3",
                "193da45d31d728acc92165173253fd8689301b448c81039350ae6916a72f157b00da469a7ab6d2b5db2dac216f47073d089afdcdffce25b6aac991f4745c803f164d7426b8da7d19bf699f5e5489f715ac32e539db90610d7df47121556c1a20",
                "938ce6cc265fa15fe67314ac4773f18ff0b49c01eac626814abc2f836814068aeba8582d619a3e0c08dc4bafecda84a818b6a7abd350637e72a47356e5919e3a72be66316417c598338e4ab85f8d25535bd6c4a5fb16767ac470902e0cf19df0",
                "985039d55a92f6fb3b324b0b9f1c7ddcd5f443d6d1ce72549f043b967ded7d56dc4320dc8569a1c41c6cfb4c150d8c61095d3b325e3308a321ceb43369fe56807fba67b6b313f072ab2cdbaa872b52a9a2e75bf396f1b2007152067f756946b7",
                "922717d8c170862662d08a4c29943cc26bb05daff0f07b0b7c352651ec64ee5a1d032bad24dfb42243e9afe044ed25680694b183b657948a91533e9a73b6bf359ff907d5088503137edc8e271ac3d2003a4daf8d36f3f847cc87afc6f9007c72",
                "02bc8e3ea8409418949fb4106e00893b49983495035b47026a1747eb6f89b05d4c1b8e357e89ddfa7c9f8145b78e0c480177842f20b98b1f7ca2ed26cfb9895380e4d86aeb60c2326bc43753a0633167a7c4ae7a526ce927ade1388a6cdc11d1"
        );
        List<String> vecIdsHex = Lists.newArrayList(
                "4393e2a243c3db776dcdbe2535493440d29cb65006a1e6f0f8d3f1e1488cbf1a",
                "8b2d776ac75cfca1559b5616ade4eb376a6478556135276e4b3210fe170b9f59",
                "f2015bdbc0daa70c41a25d2450b96f433ac7d568126505e9997794bb357cf3af",
                "5818a68f2f34e5ff7d1d43beca5ff103739dd918efda4bac7fd4ede6c53dc6af",
                "965437bdf51278f716078477a2eae595a9d2b0aa3fe69a387b30936c13c7d68e",
                "fd14695a48a35fe6a1f9894accfa83349508e350b3f743d494074fe204b17166",
                "7e3c28e59ff54bf097b2f3ada4a30f5613227951116675127fc97c98405f2067",
                "5e427dba092e3d81d057c0277a9a465e036c8336c59a18f27d7c21bc51910904",
                "90976dbe492de3eda7623c7ad6523ed9f63f83c3200c383fccd9f22408e18e1b",
                "cd474447afd5df18a0c10c9e2d5216eace9c624119974280236a043cb4b7f8ae"
        );
        int nSize = 10;
        assertEquals(vecSecretKeySharesHex.size(), nSize);
        assertEquals(vecSigSharesHex.size(), nSize);
        assertEquals(vecIdsHex.size(), nSize);

        byte[] vecSignHash = HexUtils.hexToBytes(strSignHash);
        HexUtils.reverse(vecSignHash);
        G1Element thresholdPublicKey = G1Element.fromBytes(HexUtils.hexToBytes(strThresholdPublicKey), true);
        G2Element thresholdSignatureExpected = G2Element.fromBytes(HexUtils.hexToBytes(strThresholdSignature), true);

        G2ElementVector vecSignatureShares = new G2ElementVector();
        ByteVectorList vecIds = new ByteVectorList();

        for (int i = 0; i < nSize; ++i) {
            vecIds.add(HexUtils.reverse(HexUtils.hexToBytes(vecIdsHex.get(i))));

            PrivateKey skShare = PrivateKey.fromBytes(HexUtils.hexToBytes(vecSecretKeySharesHex.get(i)));
            byte[] vecSigShareBytes = HexUtils.hexToBytes(vecSigSharesHex.get(i));
            vecSignatureShares.add(G2Element.fromBytes(vecSigShareBytes, true));
            G2Element sigShare = new LegacySchemeMPL().sign(skShare, vecSignHash);
            assertObjectEquals(sigShare, vecSignatureShares.get(vecSignatureShares.size() - 1));
            assertArrayEquals(sigShare.serialize(true), vecSigShareBytes);
        }

        G2Element thresholdSignature = DASHJBLS.signatureRecover(vecSignatureShares, vecIds);
        assertObjectEquals(thresholdSignature, thresholdSignatureExpected);
        assertTrue(new LegacySchemeMPL().verify(thresholdPublicKey, vecSignHash, thresholdSignature));
    }
}
