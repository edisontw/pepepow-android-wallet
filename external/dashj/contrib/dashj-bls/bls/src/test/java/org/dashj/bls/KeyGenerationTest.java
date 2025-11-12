/**
 * Copyright (c) 2022-present, Dash Core Group
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package org.dashj.bls;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class KeyGenerationTest extends BaseTest {
    @Test
    public void shouldGenerateKeypairFromSeed() {
        byte[] seed1 = new byte[31];
        Arrays.fill(seed1, (byte) 0x08);
        byte[] seed2 = new byte[32];
        Arrays.fill(seed2, (byte) 0x08);

        assertThrows(IllegalArgumentException.class, () -> new BasicSchemeMPL().keyGen(seed1));
        PrivateKey sk = new BasicSchemeMPL().keyGen(seed2);
        G1Element pk = sk.getG1Element();
        BLS.checkRelicErrors();
        assertEquals(pk.getFingerprint(), 0x8ee7ba56L);
    }
}
