/**
 * Copyright (c) 2022-present, Dash Core Group
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package org.dashj.bls;

import org.dashj.bls.Utils.HexUtils;
import org.junit.Test;

public class IETFTest extends BaseTest {

    @Test
    public void pyeccBector() {
        String sig1BasicHex = "96ba34fac33c7f129d602a0bc8a3d43f9abc014eceaab7359146b4b150e57b808645738f35671e9e10e0d862a30cab70074eb5831d13e6a5b162d01eebe687d0164adbd0a864370a7c222a2768d7704da254f1bf1823665bc2361f9dd8c00e99";
        String sk = "0101010101010101010101010101010101010101010101010101010101010101";
        byte[] msg = {3, 1, 4, 1, 5, 9};
        PrivateKey skobj = PrivateKey.fromBytes(HexUtils.hexToBytes(sk));
        G2Element sig = new BasicSchemeMPL().sign(skobj, msg);
        byte[] sig1 = HexUtils.hexToBytes(sig1BasicHex);
        assertObjectEquals(sig, G2Element.fromBytes(sig1));
    }
}
