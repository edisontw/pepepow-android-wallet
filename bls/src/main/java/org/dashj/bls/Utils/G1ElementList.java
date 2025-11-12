/**
 * Copyright (c) 2022-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package org.dashj.bls.Utils;

import org.dashj.bls.G1Element;
import org.dashj.bls.G1ElementVector;

import java.util.Arrays;
import java.util.List;

public class G1ElementList extends G1ElementVector {

    public G1ElementList() {
        super();
    }
    public G1ElementList(List<G1Element> list) {
        super(list);
    }

    public G1ElementList(G1Element [] array) {
        super(Arrays.asList(array));
    }

    public G1ElementList(G1Element first, G1Element... elements) {
        add(first);
        addAll(Arrays.asList(elements));
    }
}
