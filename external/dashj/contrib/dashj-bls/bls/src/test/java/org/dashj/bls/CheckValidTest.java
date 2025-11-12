/**
 * Copyright (c) 2022-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package org.dashj.bls;

import org.dashj.bls.Utils.HexUtils;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class CheckValidTest {
    @Test
    public void validPointsShouldSucceed() {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x05);
        byte[] msg1 = new byte[]{10, 11, 12};

        PrivateKey sk1 = new BasicSchemeMPL().keyGen(seed);
        G1Element pk1 = new BasicSchemeMPL().skToG1(sk1);
        pk1.checkValid();

        G2Element sig1 = new AugSchemeMPL().sign(sk1, msg1);
        sig1.checkValid();
    }

    @Test
    public void invalidG1PointsShouldNotSucceed() {
        String badPointHex =
                "8d5d0fb73b9c92df4eab4216e48c3e358578b4cc30f82c268bd6fef3bd34b558628daf1afef798d4c3b0fcd8b28c8973";

        // FromBytes throws
        assertThrows(IllegalArgumentException.class, () -> G1Element.fromBytes(HexUtils.hexToBytes(badPointHex)));

        // FromBytesUnchecked does not throw
        G1Element pk = G1Element.fromBytesUnchecked(HexUtils.hexToBytes(badPointHex));
        assertFalse(pk.isValid());
        assertThrows(IllegalArgumentException.class, pk::checkValid);

        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x05);
        byte[] msg1 = new byte[]{10, 11, 12};
        PrivateKey sk1 = new BasicSchemeMPL().keyGen(seed);
        G1Element pk1 = new BasicSchemeMPL().skToG1(sk1);
        G2Element sig1 = new AugSchemeMPL().sign(sk1, msg1);
        assertFalse(new AugSchemeMPL().verify(pk, msg1, sig1));
    }
}
