/**
 * Copyright (c) 2022-present, Dash Core Group
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package org.dashj.bls.Utils;

import com.google.common.io.BaseEncoding;

public class HexUtils {
    public static final BaseEncoding HEX = BaseEncoding.base16().lowerCase();

    static public byte [] hexToBytes(String hex) {
        return HEX.decode(hex);
    }

    public static byte[] reverse(byte[] data) {
        for (int i = 0, j = data.length - 1; i < data.length / 2; i++, j--) {
            data[i] ^= data[j];
            data[j] ^= data[i];
            data[i] ^= data[j];
        }
        return data;
    }

    public static String hexStr(byte [] bytes) {
        return HEX.encode(bytes);
    }
}
