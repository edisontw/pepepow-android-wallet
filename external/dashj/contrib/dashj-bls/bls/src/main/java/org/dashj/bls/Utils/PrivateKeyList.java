/**
 * Copyright (c) 2022-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package org.dashj.bls.Utils;

import org.dashj.bls.PrivateKey;
import org.dashj.bls.PrivateKeyVector;

import java.util.Arrays;
import java.util.List;

public class PrivateKeyList extends PrivateKeyVector {

    public PrivateKeyList() {
        super();
    }
    public PrivateKeyList(List<PrivateKey> list) {
        super(list);
    }

    public PrivateKeyList(PrivateKey [] array) {
        super(Arrays.asList(array));
    }

    public PrivateKeyList(PrivateKey first, PrivateKey... elements) {
        add(first);
        addAll(Arrays.asList(elements));
    }
}
