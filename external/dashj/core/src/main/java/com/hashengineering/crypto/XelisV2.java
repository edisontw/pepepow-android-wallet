package com.hashengineering.crypto;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Placeholder implementation for the PEPEPOW XelisV2 hashing function.
 * <p>
 * The real XelisV2 hash mixes ChaCha20, BLAKE3, and AES rounds. Until the
 * Java or JNI port is available we fall back to the legacy X11 digest so the
 * rest of the stack can compile and higher level code can begin integrating
 * against the final API surface.
 */
public final class XelisV2 {
    private static final int HASH_LENGTH = 32;

    private XelisV2() {
    }

    public static byte[] hash(byte[] data, int offset, int len) {
        Objects.requireNonNull(data, "data");
        if (offset < 0 || len < 0 || (offset + len) > data.length) {
            throw new IndexOutOfBoundsException("Invalid offset/length for XelisV2.hash");
        }
        byte[] slice = new byte[len];
        System.arraycopy(data, offset, slice, 0, len);
        byte[] digest = X11.x11Digest(slice);
        if (digest.length == HASH_LENGTH) {
            return digest;
        }
        byte[] out = new byte[HASH_LENGTH];
        System.arraycopy(digest, 0, out, 0, Math.min(digest.length, HASH_LENGTH));
        return out;
    }

    public static int hashBatch(ByteBuffer src, int itemLen, int count, ByteBuffer outAll) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(outAll, "outAll");
        if (itemLen <= 0 || count <= 0) {
            return 0;
        }
        long requiredIn = (long) itemLen * count;
        long requiredOut = (long) HASH_LENGTH * count;
        if (src.remaining() < requiredIn || outAll.remaining() < requiredOut) {
            throw new IllegalArgumentException("Insufficient buffer capacity for batch hashing");
        }
        ByteBuffer inDup = src.duplicate();
        ByteBuffer outDup = outAll.duplicate();
        byte[] tmp = new byte[itemLen];
        for (int i = 0; i < count; i++) {
            inDup.get(tmp);
            byte[] hash = hash(tmp, 0, tmp.length);
            outDup.put(hash, 0, HASH_LENGTH);
        }
        return count;
    }
}
