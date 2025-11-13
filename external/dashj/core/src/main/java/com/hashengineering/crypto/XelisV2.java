package com.hashengineering.crypto;

import io.github.rctcwyvrn.blake3.Blake3;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Pure Java implementation of the Xelis V2 proof-of-work hash.
 *
 * <p>The logic follows the reference C implementation used by the PePe-core daemon (see
 * external/PePe-core/src/crypto/xelisv2.cpp). It relies on BLAKE3, ChaCha, and a single-round AES
 * permutation to derive a large scratchpad before applying a BLAKE3 compression over the mutated
 * memory contents.</p>
 */
public final class XelisV2 {
    private static final int HASH_LENGTH = 32;
    private static final int INPUT_LENGTH = 112;
    private static final int MEM_WORDS = 429 * 128; // matches XEL_MEMSIZE
    private static final int SCRATCH_BYTES = MEM_WORDS * Long.BYTES;
    private static final int BUFFER_SIZE = MEM_WORDS / 2;
    private static final int CHUNK_SIZE = 32;
    private static final int CHUNKS = 4;
    private static final int NONCE_SIZE = 12;
    private static final int SCRATCHPAD_ITERS = 3;
    private static final int CHACHA_ROUNDS = 8;
    private static final BigInteger MASK_64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final BigInteger TWO_64 = MASK_64.add(BigInteger.ONE);
    private static final int[] CONST_STATE = {
        1634760805, 857760878, 2036477234, 1797285236
    };
    private static final byte[] AES_KEY =
            "xelishash-pow-v2".getBytes(StandardCharsets.US_ASCII);
    private static final int[] AES_SBOX = {
        0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab,
        0x76, 0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4,
        0x72, 0xc0, 0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71,
        0xd8, 0x31, 0x15, 0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2,
        0xeb, 0x27, 0xb2, 0x75, 0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6,
        0xb3, 0x29, 0xe3, 0x2f, 0x84, 0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb,
        0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf, 0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45,
        0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8, 0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5,
        0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2, 0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44,
        0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73, 0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a,
        0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb, 0xe0, 0x32, 0x3a, 0x0a, 0x49,
        0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79, 0xe7, 0xc8, 0x37, 0x6d,
        0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08, 0xba, 0x78, 0x25,
        0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a, 0x70, 0x3e,
        0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e, 0xe1,
        0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
        0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb,
        0x16
    };

    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private XelisV2() {}

    public static byte[] hash(byte[] data, int offset, int len) {
        Objects.requireNonNull(data, "data");
        if (len < 0 || offset < 0 || offset + len > data.length) {
            throw new IndexOutOfBoundsException("Invalid offset/length for XelisV2.hash");
        }
        if (len > INPUT_LENGTH) {
            throw new IllegalArgumentException("XelisV2 input must be <= " + INPUT_LENGTH + " bytes");
        }
        Workspace ws = WORKSPACE.get();
        Arrays.fill(ws.input, (byte) 0);
        System.arraycopy(data, offset, ws.input, 0, len);
        stage1(ws);
        stage3(ws);
        byte[] result = new byte[HASH_LENGTH];
        Blake3 hasher = Blake3.newInstance();
        hasher.update(ws.scratchBytes);
        byte[] digest = hasher.digest();
        System.arraycopy(digest, 0, result, 0, HASH_LENGTH);
        return result;
    }

    public static int hashBatch(ByteBuffer src, int itemLen, int count, ByteBuffer out) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(out, "out");
        if (itemLen <= 0 || count <= 0) {
            return 0;
        }
        long requiredIn = (long) itemLen * count;
        long requiredOut = (long) HASH_LENGTH * count;
        if (src.remaining() < requiredIn || out.remaining() < requiredOut) {
            throw new IllegalArgumentException("Insufficient buffer capacity for batch hashing");
        }
        ByteBuffer inDup = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer outDup = out.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        byte[] sample = new byte[itemLen];
        for (int i = 0; i < count; i++) {
            inDup.get(sample);
            byte[] hash = hash(sample, 0, sample.length);
            outDup.put(hash, 0, HASH_LENGTH);
        }
        int bytesWritten = count * HASH_LENGTH;
        out.position(out.position() + bytesWritten);
        return count;
    }

    private static void stage1(Workspace ws) {
        Arrays.fill(ws.key, (byte) 0);
        System.arraycopy(ws.input, 0, ws.key, 0, INPUT_LENGTH);
        blake3(ws.input, 0, INPUT_LENGTH, ws.buffer);
        int chunkBytes = SCRATCH_BYTES / CHUNKS;
        int scratchOffset = 0;
        for (int chunk = 0; chunk < CHUNKS; chunk++) {
            System.arraycopy(ws.key, chunk * CHUNK_SIZE, ws.buffer, CHUNK_SIZE, CHUNK_SIZE);
            blake3(ws.buffer, 0, ws.buffer.length, ws.hashBuf);
            if (chunk == 0) {
                System.arraycopy(ws.buffer, 0, ws.nonce, 0, NONCE_SIZE);
            } else {
                int nonceStart = scratchOffset - NONCE_SIZE;
                System.arraycopy(ws.scratchBytes, nonceStart, ws.nonce, 0, NONCE_SIZE);
            }
            chachaEncrypt(
                    ws.hashBuf, ws.nonce, null, ws.scratchBytes, scratchOffset, chunkBytes,
                    CHACHA_ROUNDS);
            scratchOffset += chunkBytes;
            System.arraycopy(ws.hashBuf, 0, ws.buffer, 0, CHUNK_SIZE);
        }
        for (int i = 0; i < BUFFER_SIZE; i++) {
            ws.memA[i] = littleEndianToLong(ws.scratchBytes, i * Long.BYTES);
            ws.memB[i] = littleEndianToLong(ws.scratchBytes, (BUFFER_SIZE + i) * Long.BYTES);
        }
    }

    private static void stage3(Workspace ws) {
        long[] memA = ws.memA;
        long[] memB = ws.memB;
        long addrA = memB[BUFFER_SIZE - 1];
        long r = 0;
        for (int iter = 0; iter < SCRATCHPAD_ITERS; iter++) {
            long memAVal = memA[indexFromUnsigned(addrA)];
            long memBVal = memB[indexFromUnsigned(~rotR(addrA, r))];

            longToLeBytes(memBVal, ws.block);
            longToLeBytes(memAVal, ws.block, 8);
            aesSingleRound(ws.block, AES_KEY);

            long hash1 = littleEndianToLong(ws.block, 0);
            long hash2 = memAVal ^ memBVal;
            addrA = ~(hash1 ^ hash2);

            for (int j = 0; j < BUFFER_SIZE; j++) {
                long a = memA[indexFromUnsigned(addrA)];
                long b = memB[indexFromUnsigned(~rotR(addrA, r))];
                int rIndex = (int) (r % MEM_WORDS);
                long c = (rIndex < BUFFER_SIZE) ? memA[rIndex] : memB[rIndex - BUFFER_SIZE];
                r = (r + 1) % MEM_WORDS;
                int opIdx = (int) (rotL(addrA, c) & 0xF);
                long v = applyOperation(opIdx, a, b, c, r, addrA, iter, j);
                addrA = rotL(addrA ^ v, 1);

                int targetA = BUFFER_SIZE - j - 1;
                long t = memA[targetA] ^ addrA;
                memA[targetA] = t;
                memB[j] ^= rotR(t, addrA);
            }
        }
        for (int i = 0; i < BUFFER_SIZE; i++) {
            longToLeBytes(memA[i], ws.scratchBytes, i * Long.BYTES);
            longToLeBytes(memB[i], ws.scratchBytes, (BUFFER_SIZE + i) * Long.BYTES);
        }
    }

    private static long applyOperation(
            int idx, long a, long b, long c, long r, long result, int i, int j) {
        switch (idx & 0xF) {
            case 0:
                return rotL(c, i * j) ^ b;
            case 1:
                return rotR(c, i * j) ^ a;
            case 2:
                return a ^ b ^ c;
            case 3:
                return (a + b) * c;
            case 4:
                return (b - c) * a;
            case 5:
                return c - a + b;
            case 6:
                return a - b + c;
            case 7:
                return b * c + a;
            case 8:
                return c * a + b;
            case 9:
                return a * b * c;
            case 10: {
                long divisor = c | 1L;
                return unsignedMod128(a, b, divisor);
            }
            case 11: {
                BigInteger threshold = combine(rotL(result, r), a | 2L);
                BigInteger mixed = combine(b, c);
                if (threshold.compareTo(mixed) > 0) {
                    return c;
                }
                return mixed.mod(threshold).longValue();
            }
            case 12:
                return divide128By64(c, a, b | 4L);
            case 13: {
                BigInteger t1 = combine(rotL(result, r), b);
                BigInteger t2 = combine(a, c | 8L);
                if (t1.compareTo(t2) > 0) {
                    return t1.divide(t2).longValue();
                }
                return a ^ b;
            }
            case 14: {
                BigInteger prod = combine(b, a).multiply(toUnsignedBig(c));
                return prod.shiftRight(64).and(MASK_64).longValue();
            }
            default: {
                BigInteger left = combine(a, c);
                BigInteger right = combine(rotR(result, r), b);
                BigInteger prod = left.multiply(right);
                return prod.shiftRight(64).and(MASK_64).longValue();
            }
        }
    }

    private static int indexFromUnsigned(long value) {
        long mod = Long.remainderUnsigned(value, BUFFER_SIZE);
        return (int) mod;
    }

    private static long rotL(long value, long shift) {
        return Long.rotateLeft(value, (int) (shift & 63));
    }

    private static long rotR(long value, long shift) {
        return Long.rotateRight(value, (int) (shift & 63));
    }

    private static long divide128By64(long high, long low, long divisor) {
        BigInteger dividend = combine(high, low);
        BigInteger div = toUnsignedBig(divisor);
        if (div.signum() == 0) {
            return 0;
        }
        BigInteger quotient = dividend.divide(div);
        return quotient.and(MASK_64).longValue();
    }

    private static long unsignedMod128(long high, long low, long divisor) {
        BigInteger mod = combine(high, low).mod(toUnsignedBig(divisor));
        return mod.longValue();
    }

    private static long littleEndianToLong(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static void longToLeBytes(long value, byte[] dest) {
        longToLeBytes(value, dest, 0);
    }

    private static void longToLeBytes(long value, byte[] dest, int offset) {
        ByteBuffer.wrap(dest, offset, Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value);
    }

    private static void blake3(byte[] input, int offset, int len, byte[] dest) {
        Blake3 hasher = Blake3.newInstance();
        if (offset == 0 && len == input.length) {
            hasher.update(input);
        } else {
            byte[] slice = Arrays.copyOfRange(input, offset, offset + len);
            hasher.update(slice);
        }
        byte[] digest = hasher.digest();
        System.arraycopy(digest, 0, dest, 0, HASH_LENGTH);
    }

    private static long toLong(BigInteger value) {
        return value.and(MASK_64).longValue();
    }

    private static BigInteger combine(long high, long low) {
        return toUnsignedBig(high).shiftLeft(64).add(toUnsignedBig(low));
    }

    private static BigInteger toUnsignedBig(long value) {
        BigInteger big = BigInteger.valueOf(value);
        return big.signum() >= 0 ? big : big.add(TWO_64);
    }

    private static void chachaEncrypt(
            byte[] key,
            byte[] nonce,
            byte[] in,
            byte[] out,
            int outOffset,
            int bytes,
            int rounds) {
        int[] state = new int[16];
        int[] working = new int[16];
        byte[] keystream = new byte[64];
        System.arraycopy(CONST_STATE, 0, state, 0, CONST_STATE.length);
        for (int i = 0; i < 8; i++) {
            state[4 + i] = littleEndianToInt(key, i * 4);
        }
        state[12] = 0;
        state[13] = littleEndianToInt(nonce, 0);
        state[14] = littleEndianToInt(nonce, 4);
        state[15] = littleEndianToInt(nonce, 8);

        int remaining = bytes;
        int counter = 0;
        while (remaining > 0) {
            System.arraycopy(state, 0, working, 0, state.length);
            working[12] = counter;
            for (int i = rounds; i > 0; i -= 2) {
                chachaQuarterRound(working, 0, 4, 8, 12);
                chachaQuarterRound(working, 1, 5, 9, 13);
                chachaQuarterRound(working, 2, 6, 10, 14);
                chachaQuarterRound(working, 3, 7, 11, 15);
                chachaQuarterRound(working, 0, 5, 10, 15);
                chachaQuarterRound(working, 1, 6, 11, 12);
                chachaQuarterRound(working, 2, 7, 8, 13);
                chachaQuarterRound(working, 3, 4, 9, 14);
            }
            for (int i = 0; i < 16; i++) {
                int value;
                if (i < 4) {
                    value = working[i] + CONST_STATE[i];
                } else if (i < 12) {
                    value = working[i] + state[i];
                } else {
                    value = working[i] + ((i == 12) ? counter : state[i]);
                }
                intToLittleEndian(value, keystream, i * 4);
            }
            int block = Math.min(remaining, 64);
            if (in == null) {
                System.arraycopy(keystream, 0, out, outOffset, block);
            } else {
                for (int i = 0; i < block; i++) {
                    out[outOffset + i] = (byte) (keystream[i] ^ in[outOffset + i]);
                }
            }
            remaining -= block;
            outOffset += block;
            counter++;
        }
    }

    private static void chachaQuarterRound(int[] s, int a, int b, int c, int d) {
        s[a] += s[b];
        s[d] = Integer.rotateLeft(s[d] ^ s[a], 16);
        s[c] += s[d];
        s[b] = Integer.rotateLeft(s[b] ^ s[c], 12);
        s[a] += s[b];
        s[d] = Integer.rotateLeft(s[d] ^ s[a], 8);
        s[c] += s[d];
        s[b] = Integer.rotateLeft(s[b] ^ s[c], 7);
    }

    private static int littleEndianToInt(byte[] data, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getInt();
    }

    private static void intToLittleEndian(int value, byte[] out, int offset) {
        ByteBuffer.wrap(out, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }

    private static void aesSingleRound(byte[] state, byte[] roundKey) {
        subBytes(state);
        shiftRows(state);
        mixColumns(state);
        addRoundKey(state, roundKey);
    }

    private static void subBytes(byte[] state) {
        for (int i = 0; i < state.length; i++) {
            state[i] = (byte) AES_SBOX[state[i] & 0xFF];
        }
    }

    private static void shiftRows(byte[] state) {
        byte[] temp = Arrays.copyOf(state, state.length);
        state[1] = temp[5];
        state[5] = temp[9];
        state[9] = temp[13];
        state[13] = temp[1];

        state[2] = temp[10];
        state[6] = temp[14];
        state[10] = temp[2];
        state[14] = temp[6];

        state[3] = temp[15];
        state[7] = temp[3];
        state[11] = temp[7];
        state[15] = temp[11];

        state[4] = temp[4];
        state[8] = temp[8];
        state[12] = temp[12];
        state[0] = temp[0];
    }

    private static void mixColumns(byte[] state) {
        byte[] temp = new byte[16];
        for (int i = 0; i < 4; i++) {
            int base = i * 4;
            temp[base] =
                    (byte)
                            (gmul((byte) 0x02, state[base])
                                    ^ gmul((byte) 0x03, state[base + 1])
                                    ^ state[base + 2]
                                    ^ state[base + 3]);
            temp[base + 1] =
                    (byte)
                            (state[base]
                                    ^ gmul((byte) 0x02, state[base + 1])
                                    ^ gmul((byte) 0x03, state[base + 2])
                                    ^ state[base + 3]);
            temp[base + 2] =
                    (byte)
                            (state[base]
                                    ^ state[base + 1]
                                    ^ gmul((byte) 0x02, state[base + 2])
                                    ^ gmul((byte) 0x03, state[base + 3]));
            temp[base + 3] =
                    (byte)
                            (gmul((byte) 0x03, state[base])
                                    ^ state[base + 1]
                                    ^ state[base + 2]
                                    ^ gmul((byte) 0x02, state[base + 3]));
        }
        System.arraycopy(temp, 0, state, 0, temp.length);
    }

    private static void addRoundKey(byte[] state, byte[] roundKey) {
        for (int i = 0; i < state.length; i++) {
            state[i] ^= roundKey[i];
        }
    }

    private static byte gmul(byte a, byte b) {
        byte p = 0;
        byte hiBitSet;
        byte bb = b;
        byte aa = a;
        for (int counter = 0; counter < 8; counter++) {
            if ((bb & 1) != 0) {
                p ^= aa;
            }
            hiBitSet = (byte) (aa & 0x80);
            aa <<= 1;
            if (hiBitSet != 0) {
                aa ^= 0x1b;
            }
            bb >>= 1;
        }
        return p;
    }

    private static final class Workspace {
        final byte[] input = new byte[INPUT_LENGTH];
        final byte[] scratchBytes = new byte[SCRATCH_BYTES];
        final long[] memA = new long[BUFFER_SIZE];
        final long[] memB = new long[BUFFER_SIZE];
        final byte[] key = new byte[CHUNK_SIZE * CHUNKS];
        final byte[] buffer = new byte[CHUNK_SIZE * 2];
        final byte[] hashBuf = new byte[HASH_LENGTH];
        final byte[] nonce = new byte[NONCE_SIZE];
        final byte[] block = new byte[16];
    }
}
