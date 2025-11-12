/**
 * Copyright (c) 2022-present, Dash Core Group
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package org.dashj.bls;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ErrorHandlingTest extends BaseTest {
    @Test
    public void shouldThrowOnBadPrivateKey() {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x10);
        PrivateKey sk1 = new BasicSchemeMPL().keyGen(seed);
        byte[] skData = new byte[G2Element.SIZE];
        sk1.serialize(skData);
        skData[0] = (byte) 255;
        assertThrows(IllegalArgumentException.class, () -> PrivateKey.fromBytes(skData));
    }

    @Test
    public void shouldThrowOnBadPublicKey() {
        byte[] buf = new byte[G1Element.SIZE];
        for (int i = 0; i < 0xFF; i++) {
            buf[0] = (byte) i;
            if (i == 0xc0) { // Infinity prefix shouldn't throw here as we have only zero values
                G1Element.fromBytes(buf);
            } else {
                assertThrows(IllegalArgumentException.class, () -> G1Element.fromBytes(buf));
            }
        }
    }

    @Test
    public void shouldThrowOnBadG2Element() {
        byte[] buf = new byte[G2Element.SIZE];

        for (int i = 0; i < 0xFF; i++) {
            buf[0] = (byte) i;
            if (i == 0xc0) { // Infinity prefix shouldn't throw here as we have only zero values
                G2Element.fromBytes(buf);
            } else {
                assertThrows(IllegalArgumentException.class, () -> G2Element.fromBytes(buf));
            }
        }
        // Trigger "G2 element must always have 48th byte start with 0b000" error case
        buf[48] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> G2Element.fromBytes(buf));
    }

    @Test
    public void errorHandlingShouldBeThreadSafe() throws InterruptedException {
        BLS.setContextError(10);
        assertEquals(BLS.getContextError(), 10);

        long ctx1 = BLS.getContext();

        // spawn a thread and make sure it uses a different/same context depending on relic's multithreading setup
        Thread thread = new Thread(() -> {
            BLS.init();
            assertNotEquals(ctx1, BLS.getContext());

            assertNotEquals(BLS.getContextError(), BLS.RLC_OK);
            // this should not modify the code of the main thread
            BLS.setContextError(1);
        });
        thread.start();

        thread.join();

        // other thread should not modify code
        assertEquals(10, BLS.getContextError());

        // reset so that future test cases don't fail
        BLS.setContextError(BLS.RLC_OK);
    }
}
