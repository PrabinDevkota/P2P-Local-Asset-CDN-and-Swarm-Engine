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
import java.util.Objects;

/**
 * Publisher-side glue: one pass over the file builds the catalog and stores
 * each slice. ChunkStore still re-hashes on put (fail closed).
 */
public final class AssetIngestor {

    private final FileChunker chunker;
    private final ChunkStore store;

    public AssetIngestor(FileChunker chunker, ChunkStore store) {
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.store = Objects.requireNonNull(store, "store");
    }

    public List<ChunkEntry> ingest(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        int chunkSize = chunker.chunkSize();
        try (FileChannel channel = FileChannel.open(file)) {
            long fileSize = channel.size();
            if (fileSize == 0) {
                return List.of();
            }
            MessageDigest sha256 = sha256();
            ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
            List<ChunkEntry> chunks = new ArrayList<>();
            long offset = 0;
            int index = 0;
            while (offset < fileSize) {
                int length = (int) Math.min(chunkSize, fileSize - offset);
                byte[] data = readSlice(channel, buffer, offset, length);
                sha256.reset();
                String hash = Hex.toLowerHex(sha256.digest(data));
                store.putVerified(hash, data);
                chunks.add(new ChunkEntry(index, offset, length, hash));
                offset += length;
                index++;
            }
            return List.copyOf(chunks);
        }
    }

    private static byte[] readSlice(FileChannel channel, ByteBuffer buffer, long offset, int length)
            throws IOException {
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
        byte[] data = new byte[length];
        buffer.flip();
        buffer.get(data);
        return data;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
