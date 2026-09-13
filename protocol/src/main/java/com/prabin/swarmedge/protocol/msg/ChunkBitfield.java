package com.prabin.swarmedge.protocol.msg;

import com.prabin.swarmedge.protocol.ProtocolViolationException;

import java.util.Objects;

/**
 * Bit order for the BITFIELD payload.
 *
 * <p>Chunk {@code i} is bit {@code 7 - (i % 8)} of byte {@code i / 8}: the first chunk
 * is the most significant bit of the first byte. Reading a bitfield left to right in a
 * hex dump therefore reads chunks in order, which is why the convention is worth pinning
 * down rather than leaving to each implementation.
 *
 * <p>Padding bits in the final byte must be zero. Two peers with the same inventory must
 * produce the same bytes, so a set padding bit is a protocol violation rather than
 * something to ignore politely.
 */
public final class ChunkBitfield {

    private ChunkBitfield() {
    }

    public static int byteLength(int bitCount) {
        return Messages.bitfieldByteLength(bitCount);
    }

    public static byte[] empty(int bitCount) {
        return new byte[byteLength(bitCount)];
    }

    public static void set(byte[] bits, int index) {
        checkIndex(bits, index);
        bits[index >>> 3] |= (byte) (0x80 >>> (index & 7));
    }

    public static boolean get(byte[] bits, int index) {
        checkIndex(bits, index);
        return (bits[index >>> 3] & (0x80 >>> (index & 7))) != 0;
    }

    public static int cardinality(byte[] bits, int bitCount) {
        int found = 0;
        for (int i = 0; i < bitCount; i++) {
            if (get(bits, i)) {
                found++;
            }
        }
        return found;
    }

    /** Fail closed on a bitfield that is the wrong size or has padding bits set. */
    public static void validate(byte[] bits, int bitCount) {
        Objects.requireNonNull(bits, "bits");
        int expected = byteLength(bitCount);
        if (bits.length != expected) {
            throw new ProtocolViolationException(
                    "BITFIELD must be " + expected + " bytes for " + bitCount + " chunks");
        }
        int padding = expected * 8 - bitCount;
        if (padding == 0) {
            return;
        }
        int mask = 0xFF >>> (8 - padding);
        if ((bits[expected - 1] & mask) != 0) {
            throw new ProtocolViolationException("BITFIELD padding bits must be zero");
        }
    }

    private static void checkIndex(byte[] bits, int index) {
        if (index < 0 || index >>> 3 >= bits.length) {
            throw new IndexOutOfBoundsException("bit index out of range: " + index);
        }
    }
}
