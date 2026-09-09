package com.prabin.swarmedge.manifest;

import com.prabin.swarmedge.common.Defaults;
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
 * Splits a file into fixed-size integrity chunks and SHA-256 hashes each one.
 * Catalog only — {@link ChunkStore} / {@link AssetIngestor} write bytes.
 */
public final class FileChunker {

    private final int chunkSize;

    public FileChunker() {
        this(Defaults.CHUNK_SIZE_BYTES);
    }

    public FileChunker(long chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (chunkSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("chunkSize must fit in a byte array");
        }
        this.chunkSize = (int) chunkSize;
    }

    public int chunkSize() {
        return chunkSize;
    }

    public List<ChunkEntry> chunk(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file)) {
            long fileSize = channel.size();
            List<ChunkEntry> chunks = new ArrayList<>();
            if (fileSize == 0) {
                return List.of();
            }

            MessageDigest sha256 = sha256();
            ByteBuffer buffer = ByteBuffer.allocateDirect(chunkSize);
            long offset = 0;
            int index = 0;

            while (offset < fileSize) {
                int length = (int) Math.min(chunkSize, fileSize - offset);
                buffer.clear();
                buffer.limit(length);

                int read = 0;
                while (read < length) {
                    int n = channel.read(buffer, offset + read);
                    if (n < 0) {
                        throw new IOException("unexpected end of file at offset " + (offset + read));
                    }
                    read += n;
                }

                buffer.flip();
                sha256.reset();
                sha256.update(buffer);
                String hash = Hex.toLowerHex(sha256.digest());
                chunks.add(new ChunkEntry(index, offset, length, hash));

                offset += length;
                index++;
            }
            return List.copyOf(chunks);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
