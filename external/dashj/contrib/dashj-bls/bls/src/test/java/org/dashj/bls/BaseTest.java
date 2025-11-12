/**
 * Copyright (c) 2018-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package org.dashj.bls;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Created by hashengineering on 11/13/18.
 */
public class BaseTest {
    static {
        BLSJniLibrary.init();
    }

    static void assertObjectEquals(G1Element a, G1Element b) {
        assertTrue(DASHJBLS.objectEquals(a, b));
    }

    static void assertObjectEquals(G2Element a, G2Element b) {
        assertTrue(DASHJBLS.objectEquals(a, b));
    }

    static void assertObjectEquals(PrivateKey a, PrivateKey b) {
        assertTrue(DASHJBLS.objectEquals(a, b));
    }

    static void assertObjectEquals(ExtendedPublicKey a, ExtendedPublicKey b) {
        assertTrue(DASHJBLS.objectEquals(a, b));
    }

    static void assertObjectEquals(ExtendedPrivateKey a, ExtendedPrivateKey b) {
        assertTrue(DASHJBLS.objectEquals(a, b));
    }

    static void assertObjectNotEquals(G1Element a, G1Element b) {
        assertFalse(DASHJBLS.objectEquals(a, b));
    }

    static void assertObjectNotEquals(G2Element a, G2Element b) {
        assertFalse(DASHJBLS.objectEquals(a, b));
    }

    static void assertObjectNotEquals(PrivateKey a, PrivateKey b) {
        assertFalse(DASHJBLS.objectEquals(a, b));
    }
}
