/**
 * Copyright (c) 2022-present, Dash Core Group
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package org.dashj.bls;

import org.dashj.bls.Utils.ByteVector;

import java.security.SecureRandom;

public class Entropy {
    static SecureRandom secureRandom;

    static {
        secureRandom = new SecureRandom();
    }

    public static byte[] getRandomSeed(int size) {
        byte[] bytes = new byte[size];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    public static Uint8Vector getRandomSeedAsUint8Vector(int size) {
        byte[] bytes = new byte[size];
        secureRandom.nextBytes(bytes);
        return new ByteVector(bytes);
    }
}
