package com.hashengineering.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.bitcoinj.core.Utils;
import org.junit.Test;

public class XelisV2Test {

    @Test
    public void deterministicHashMatchesVector() {
        byte[] input = new byte[112];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) i;
        }
        byte[] result = XelisV2.hash(input, 0, input.length);
        String hex = Utils.HEX.encode(result);
        assertEquals("fe2be82e0ed570125c57d75e7e8424baacb7d3b21cf906df819859e3ee9c81fe", hex);
    }

    @Test
    public void batchHashingProducesSameResults() {
        int itemLen = 112;
        int count = 3;
        ByteBuffer in = ByteBuffer.allocate(itemLen * count);
        for (int i = 0; i < count; i++) {
            byte[] sample = new byte[itemLen];
            Arrays.fill(sample, (byte) (i + 1));
            in.put(sample);
        }
        in.flip();
        ByteBuffer out = ByteBuffer.allocate(count * 32);
        int produced = XelisV2.hashBatch(in, itemLen, count, out);
        assertEquals(count, produced);
        out.flip();
        for (int i = 0; i < count; i++) {
            byte[] expected = new byte[32];
            byte[] sample = new byte[itemLen];
            Arrays.fill(sample, (byte) (i + 1));
            expected = XelisV2.hash(sample, 0, sample.length);
            byte[] actual = new byte[32];
            out.get(actual);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    public void rejectsOversizedInput() {
        byte[] input = new byte[113];
        assertThrows(IllegalArgumentException.class, () -> XelisV2.hash(input, 0, input.length));
    }
}
