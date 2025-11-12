/**
 * Copyright (c) 2022-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package org.dashj.bls;

import org.dashj.bls.Utils.HexUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EIP2333Test extends BaseTest{
    void TestEIP2333(String seedHex, String masterSkHex, String childSkHex, long childIndex) {
        byte [] masterSk = HexUtils.hexToBytes(masterSkHex);
        byte [] childSk = HexUtils.hexToBytes(childSkHex);

        PrivateKey master = new BasicSchemeMPL().keyGen(HexUtils.hexToBytes(seedHex));
        PrivateKey child = HDKeys.deriveChildSk(master, childIndex);

        byte [] master_arr = new byte[32];
        master.serialize(master_arr);
        byte [] calculatedMaster = master.serialize();
        byte [] calculatedChild = child.serialize();

        assertEquals(calculatedMaster.length, 32);
        assertEquals(calculatedChild.length, 32);
        for (int i=0; i<32; i++) {
            assertEquals(calculatedMaster[i], masterSk[i]);
        }
        for (int i=0; i<32; i++) {
            assertEquals(calculatedChild[i], childSk[i]);
        }
    }

    // EIP-2333 hardened HD keys

    // The comments in the test cases correspond to  integers that are converted to
    // bytes using python int.to_bytes(32, "big").hex(), since the EIP spec provides ints, but c++
    // does not support bigint by default
    @Test
    public void eip2333TestCase1() {
        TestEIP2333("3141592653589793238462643383279502884197169399375105820974944592",
                // 36167147331491996618072159372207345412841461318189449162487002442599770291484
                "4ff5e145590ed7b71e577bb04032396d1619ff41cb4e350053ed2dce8d1efd1c",
                // 41787458189896526028601807066547832426569899195138584349427756863968330588237
                "5c62dcf9654481292aafa3348f1d1b0017bbfb44d6881d26d2b17836b38f204d",
                3141592653L
        );
    }
    @Test
    public void eip2333TestCase2() {
        TestEIP2333("0099FF991111002299DD7744EE3355BBDD8844115566CC55663355668888CC00".toLowerCase(),
                // 13904094584487173309420026178174172335998687531503061311232927109397516192843
                "1ebd704b86732c3f05f30563dee6189838e73998ebc9c209ccff422adee10c4b",
                // 12482522899285304316694838079579801944734479969002030150864436005368716366140
                "1b98db8b24296038eae3f64c25d693a269ef1e4d7ae0f691c572a46cf3c0913c",
                4294967295L
        );
    }
    @Test
    public void eip2333TestCase3() {
        TestEIP2333("d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3",
                // 44010626067374404458092393860968061149521094673473131545188652121635313364506
                "614d21b10c0e4996ac0608e0e7452d5720d95d20fe03c59a3321000a42432e1a",
                // 4011524214304750350566588165922015929937602165683407445189263506512578573606
                "08de7136e4afc56ae3ec03b20517d9c1232705a747f588fd17832f36ae337526",
                42
        );
    }
    @Test
    public void eip2333TestVectorWithIntermediateValues() {
        TestEIP2333("c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
                // 5399117110774477986698372024995405256382522670366369834617409486544348441851
                "0befcabff4a664461cc8f190cdd51c05621eb2837c71a1362df5b465a674ecfb",
                // 11812940737387919040225825939013910852517748782307378293770044673328955938106
                "1a1de3346883401f1e3b2281be5774080edb8e5ebe6f776b0f7af9fea942553a",
                0
        );
    }
}
