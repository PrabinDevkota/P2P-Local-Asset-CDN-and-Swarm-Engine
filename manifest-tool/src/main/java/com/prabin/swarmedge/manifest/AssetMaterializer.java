package com.prabin.swarmedge.manifest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * Leecher-side glue: read verified slices from ChunkStore and stitch them back
 * into the original file. Missing or tampered chunks fail closed; destination
 * is replaced only after the full file is assembled.
 */
public final class AssetMaterializer {

    private final ChunkStore store;

    public AssetMaterializer(ChunkStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void materialize(List<ChunkEntry> chunks, Path destination) throws IOException {
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(destination, "destination");
        Path parent = destination.getParent();
        if (parent == null) {
            parent = destination.toAbsolutePath().getParent();
        }
        if (parent == null) {
            parent = Path.of(".");
        }
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "materialize-", ".tmp");
        try {
            long fileSize = 0;
            for (ChunkEntry chunk : chunks) {
                fileSize = Math.max(fileSize, Math.addExact(chunk.offset(), chunk.length()));
            }
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (ChunkEntry chunk : chunks) {
                    byte[] data = readVerified(chunk);
                    channel.write(ByteBuffer.wrap(data), chunk.offset());
                }
                channel.truncate(fileSize);
            }
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private byte[] readVerified(ChunkEntry chunk) throws IOException {
        byte[] data = store.read(chunk.sha256())
                .orElseThrow(() -> new IOException("missing chunk " + chunk.sha256()));
        if (data.length != chunk.length()) {
            throw new IllegalArgumentException("chunk length mismatch: expected " + chunk.length()
                    + " but was " + data.length);
        }
        return data;
    }
}
