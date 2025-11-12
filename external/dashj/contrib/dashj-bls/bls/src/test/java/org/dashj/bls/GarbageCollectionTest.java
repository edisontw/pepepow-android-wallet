package org.dashj.bls;

import org.junit.Test;


import static org.junit.Assert.assertTrue;

public class GarbageCollectionTest {
    @Test
    public void gcTest() {
        PrivateKey sk = PrivateKey.fromSeedBIP32(Entropy.getRandomSeed(PrivateKey.PRIVATE_KEY_SIZE));
        G1Element pk = sk.getG1Element();

        BasicSchemeMPL scheme = new BasicSchemeMPL();
        byte [] message = Entropy.getRandomSeed(32);
        G2Element sig = scheme.sign(sk, message);
        assertTrue(scheme.verify(pk, message, sig));

        sk = null;
        pk = null;
        sig = null;
        message = null;
        System.gc();
    }
}
