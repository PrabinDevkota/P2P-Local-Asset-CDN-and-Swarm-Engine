package com.prabin.swarmedge.manifest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Publisher-side glue: one pass over the file builds the catalog and stores
 * each slice. ChunkStore still re-hashes on put (fail closed).
 */
public final class AssetIngestor {

    private final Chunker chunker;
    private final ChunkStore store;

    public AssetIngestor(Chunker chunker, ChunkStore store) {
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.store = Objects.requireNonNull(store, "store");
    }

    public List<ChunkEntry> ingest(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        List<ChunkEntry> catalog = chunker.chunk(file);
        if (catalog.isEmpty()) {
            return List.of();
        }
        int window = windowSize(catalog);
        try (FileChannel channel = FileChannel.open(file)) {
            ByteBuffer buffer = ByteBuffer.allocate(window);
            for (ChunkEntry chunk : catalog) {
                byte[] data = readSlice(channel, buffer, chunk.offset(), (int) chunk.length());
                store.putVerified(chunk.sha256(), data);
            }
            return catalog;
        }
    }

    private static int windowSize(List<ChunkEntry> catalog) {
        long max = 1;
        for (ChunkEntry chunk : catalog) {
            max = Math.max(max, chunk.length());
        }
        if (max > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("chunk longer than a byte array");
        }
        return (int) max;
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
}
