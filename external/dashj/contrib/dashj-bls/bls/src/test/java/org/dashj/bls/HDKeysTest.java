/**
 * Copyright (c) 2022-present, Dash Core Group
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package org.dashj.bls;

import org.junit.Test;

public class HDKeysTest extends BaseTest {
    @Test
    public void shouldMatchDerivationThroughPrivateAndPublicKeys() {
        byte[] seed = new byte[]{1, 50, 6, (byte) 244, 24, (byte) 199, 1, 25, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29};

        PrivateKey sk = new BasicSchemeMPL().keyGen(seed);
        G1Element pk = sk.getG1Element();

        PrivateKey childSk = new BasicSchemeMPL().deriveChildSkUnhardened(sk, 42);
        G1Element childPk = new BasicSchemeMPL().deriveChildPkUnhardened(pk, 42);

        assertObjectEquals(childSk.getG1Element(), childPk);

        PrivateKey grandchildSk = new BasicSchemeMPL().deriveChildSkUnhardened(childSk, 12142);
        G1Element grandcihldPk = new BasicSchemeMPL().deriveChildPkUnhardened(childPk, 12142);

        assertObjectEquals(grandchildSk.getG1Element(), grandcihldPk);
    }

    @Test
    public void shouldDerivePublicChildFromParent() {
        byte[] seed = new byte[]{2, 50, 6, (byte) 244, 24, (byte) 199, 1, 25, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29};

        PrivateKey sk = new BasicSchemeMPL().keyGen(seed);
        G1Element pk = sk.getG1Element();

        PrivateKey childSk = new BasicSchemeMPL().deriveChildSkUnhardened(sk, 42);
        G1Element childPk = new BasicSchemeMPL().deriveChildPkUnhardened(pk, 42);

        PrivateKey childSkHardened = new BasicSchemeMPL().deriveChildSk(sk, 42);
        assertObjectEquals(childSk.getG1Element(), childPk);
        assertObjectNotEquals(childSkHardened, childSk);
        assertObjectNotEquals(childSkHardened.getG1Element(), childPk);
    }
}
