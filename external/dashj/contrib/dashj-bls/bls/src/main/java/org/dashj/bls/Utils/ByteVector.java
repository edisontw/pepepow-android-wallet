/**
 * Copyright (c) 2022-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package org.dashj.bls.Utils;

import org.dashj.bls.Uint8Vector;

public class ByteVector extends Uint8Vector {

    public ByteVector() {
        super();
    }

    public ByteVector(ByteVector byteVector) {
        super(byteVector);
    }

    public ByteVector(Iterable<Short> initialElements) {
        super(initialElements);
    }
    public ByteVector(byte[] byteArray) {
        for (byte b : byteArray) {
            add((short)b);
        }
    }

    public ByteVector(short[] shortArray) {
        super(shortArray);
    }

    public ByteVector(int count, short value) {
        super(count, value);
    }
}
