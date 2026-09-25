package com.prabin.swarmedge.manifest;

import com.prabin.swarmedge.common.id.Hex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Content-defined chunking (FastCDC, normalized gear hash).
 *
 * <p>Cuts depend on the bytes, so an insert moves the boundary after the edit
 * and leaves earlier chunks addressable. The gear table is derived from a fixed
 * seed so two processes cut the same file the same way. This is not a claim
 * about dedup ratio.
 */
public final class FastCdcChunker implements Chunker {

    private static final long GEAR_SEED = 0x46617374434443L;
    private static final long[] GEAR = gearTable();

    private final int minSize;
    private final int avgSize;
    private final int maxSize;
    private final long maskSmall;
    private final long maskLarge;

    public FastCdcChunker(int minSize, int avgSize, int maxSize) {
        if (minSize <= 0 || minSize > avgSize || avgSize > maxSize) {
            throw new IllegalArgumentException("require 0 < min <= average <= max");
        }
        this.minSize = minSize;
        this.avgSize = avgSize;
        this.maxSize = maxSize;
        int bits = 31 - Integer.numberOfLeadingZeros(avgSize);
        this.maskSmall = bits >= 31 ? -1L : (1L << (bits + 1)) - 1;
        this.maskLarge = bits <= 1 ? 0L : (1L << (bits - 1)) - 1;
    }

    @Override
    public ChunkingSpec spec() {
        return new ChunkingSpec(ManifestValidator.MODE_FASTCDC, avgSize, minSize, maxSize);
    }

    public int minSize() {
        return minSize;
    }

    public int averageSize() {
        return avgSize;
    }

    public int maxSize() {
        return maxSize;
    }

    @Override
    public List<ChunkEntry> chunk(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file)) {
            long fileSize = channel.size();
            if (fileSize == 0) {
                return List.of();
            }
            MessageDigest sha256 = sha256();
            ByteBuffer window = ByteBuffer.allocate(maxSize);
            List<ChunkEntry> chunks = new ArrayList<>();
            long offset = 0;
            int index = 0;
            while (offset < fileSize) {
                int available = (int) Math.min(maxSize, fileSize - offset);
                window.clear();
                window.limit(available);
                int read = 0;
                while (read < available) {
                    int n = channel.read(window, offset + read);
                    if (n < 0) {
                        throw new IOException("unexpected end of file at offset " + (offset + read));
                    }
                    read += n;
                }
                byte[] bytes = new byte[available];
                window.flip();
                window.get(bytes);
                int length = cut(bytes);
                sha256.reset();
                sha256.update(bytes, 0, length);
                chunks.add(new ChunkEntry(index, offset, length, Hex.toLowerHex(sha256.digest())));
                offset += length;
                index++;
            }
            return List.copyOf(chunks);
        }
    }

    /** @return cut length in {@code 1..data.length}, and at most {@code maxSize} */
    int cut(byte[] data) {
        if (data.length <= minSize) {
            return data.length;
        }
        long fingerprint = 0;
        int limit = Math.min(data.length, maxSize);
        for (int i = 0; i < minSize; i++) {
            fingerprint = (fingerprint << 1) + GEAR[data[i] & 0xFF];
        }
        for (int i = minSize; i < limit; i++) {
            fingerprint = (fingerprint << 1) + GEAR[data[i] & 0xFF];
            long mask = i < avgSize ? maskSmall : maskLarge;
            if ((fingerprint & mask) == 0) {
                return i + 1;
            }
        }
        return limit;
    }

    private static long[] gearTable() {
        long[] table = new long[256];
        long state = GEAR_SEED;
        for (int i = 0; i < table.length; i++) {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            table[i] = z ^ (z >>> 31);
        }
        return table;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
