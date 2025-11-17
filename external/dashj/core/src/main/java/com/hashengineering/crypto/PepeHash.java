/*
 * Copyright 2024 HashEngineering
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hashengineering.crypto;

import fr.cryptohash.BLAKE512;
import fr.cryptohash.CubeHash512;
import fr.cryptohash.Digest;
import fr.cryptohash.ECHO512;
import fr.cryptohash.SHAvite512;
import fr.cryptohash.SIMD512;
import org.bitcoinj.core.Sha256Hash;

/**
 * Implements the PePeHash chain (BLAKE -> SIMD -> ECHO -> CubeHash -> SHAvite -> SHA256 x3).
 */
public class PepeHash {

    private static Digest[] initAlgorithms() {
        return new Digest[]{
                new BLAKE512(),
                new SIMD512(),
                new ECHO512(),
                new CubeHash512(),
                new SHAvite512()
        };
    }

    private static byte[] padTo64Bytes(byte[] input) {
        if (input.length >= 64) {
            return input;
        }
        byte[] padded = new byte[64];
        System.arraycopy(input, 0, padded, 0, input.length);
        return padded;
    }

    public static byte[] pepeDigest(byte[] input) {
        Digest[] algorithms = initAlgorithms();
        byte[] current = input;
        for (Digest algorithm : algorithms) {
            algorithm.reset();
            algorithm.update(current, 0, current.length);
            current = algorithm.digest();
        }

        current = padTo64Bytes(Sha256Hash.hash(current));
        current = padTo64Bytes(Sha256Hash.hash(current));
        current = Sha256Hash.hash(current);

        return current;
    }
}
