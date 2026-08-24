package com.prabin.swarmedge.manifest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Publisher-side glue: FileChunker builds the hash catalog; ChunkStore keeps only
 * verified bytes. Walks the original file and stores each slice under its SHA-256.
 */
public final class AssetIngestor {

    private final FileChunker chunker;
    private final ChunkStore store;

    public AssetIngestor(FileChunker chunker, ChunkStore store) {
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Hash the file into {@link ChunkEntry} rows, then {@code putVerified} each slice.
     * The store re-hashes; a mismatch throws and that chunk is not kept.
     */
    public List<ChunkEntry> ingest(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        List<ChunkEntry> chunks = chunker.chunk(file);
        try (FileChannel channel = FileChannel.open(file)) {
            for (ChunkEntry chunk : chunks) {
                store.putVerified(chunk.sha256(), readSlice(channel, chunk));
            }
        }
        return chunks;
    }

    private static byte[] readSlice(FileChannel channel, ChunkEntry chunk) throws IOException {
        int length = Math.toIntExact(chunk.length());
        ByteBuffer buffer = ByteBuffer.allocate(length);
        int read = 0;
        while (read < length) {
            int n = channel.read(buffer, chunk.offset() + read);
            if (n < 0) {
                throw new IOException("unexpected end of file at offset " + (chunk.offset() + read));
            }
            read += n;
        }
        return buffer.array();
    }
}
