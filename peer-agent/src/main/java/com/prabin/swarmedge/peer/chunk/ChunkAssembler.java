package com.prabin.swarmedge.peer.chunk;

import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Lands incoming block bytes in a staging file and commits the chunk once it verifies.
 *
 * <p>Blocks are written straight to disk at their offset, so the largest thing in heap
 * is whatever the socket just handed over, not the 4 MiB chunk being built. The chunk
 * is hashed from the staging file and only then moved into the content-addressed store,
 * which is the point where unverified bytes become trusted bytes.
 *
 * <p>A hash mismatch throws {@link VerificationFailed} and leaves nothing behind: the
 * staging file is gone and the chunk can be fetched again from a different peer.
 *
 * <p>Staging files are deliberately not reused across restarts. Which byte ranges had
 * landed is in-memory state, so a leftover {@code .part} file from a previous run tells
 * us nothing trustworthy about its own contents. Completed chunks survive in the store;
 * a chunk that was half-built starts over.
 *
 * <p>Every method touches the filesystem, so callers must invoke them off the Netty
 * event loop (blueprint §21.2). The methods are synchronized so that a disk executor
 * and a shutdown path cannot race on the same channel.
 */
public final class ChunkAssembler implements Closeable {

    private static final String SUFFIX = ".part";

    private final ChunkInventory inventory;
    private final ChunkStore store;
    private final Path stagingDir;
    private final Map<Integer, Partial> open = new HashMap<>();
    /** Bumped when a round of this chunk is thrown away, so in-flight writes from it cannot seed the next. */
    private final Map<Integer, Integer> epoch = new HashMap<>();

    public ChunkAssembler(ChunkInventory inventory, ChunkStore store, Path stagingDir) throws IOException {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.store = Objects.requireNonNull(store, "store");
        this.stagingDir = Objects.requireNonNull(stagingDir, "stagingDir");
        Files.createDirectories(stagingDir);
        deleteStaleStaging();
    }

    /**
     * Write part of a block at its place inside the chunk.
     *
     * @param offsetInChunk absolute offset within the chunk, not within the block
     */
    public synchronized void accept(int chunkIndex, long offsetInChunk, byte[] bytes, int off, int len)
            throws IOException {
        accept(chunkIndex, offsetInChunk, bytes, off, len, epoch(chunkIndex));
    }

    /**
     * Same as {@link #accept(int, long, byte[], int, int)}, but drops the write when it
     * belongs to a round that has already been thrown away.
     *
     * <p>The disk executor is a queue. A hash mismatch can bump the epoch while a write
     * from the failed round is still waiting, and that write must not open a new staging
     * file — those bytes are why the last attempt failed.
     */
    public synchronized void accept(int chunkIndex, long offsetInChunk, byte[] bytes, int off, int len,
                                    int expectedEpoch) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (off < 0 || len <= 0 || off + len > bytes.length) {
            throw new IllegalArgumentException("slice out of bounds: off=" + off + " len=" + len);
        }
        inventory.requireInRange(chunkIndex, offsetInChunk, len);
        if (inventory.has(chunkIndex) || expectedEpoch != epoch(chunkIndex)) {
            return;
        }

        Partial partial = openPartial(chunkIndex);
        ByteBuffer buffer = ByteBuffer.wrap(bytes, off, len);
        long position = offsetInChunk;
        while (buffer.hasRemaining()) {
            int written = partial.channel.write(buffer, position);
            if (written <= 0) {
                throw new IOException("staging write made no progress at offset " + position);
            }
            position += written;
        }
        partial.received.add(offsetInChunk, offsetInChunk + len);
    }

    /** The assembly round this chunk is in. Capture it before handing work to the disk thread. */
    public synchronized int epoch(int chunkIndex) {
        return epoch.getOrDefault(chunkIndex, 0);
    }

    public synchronized boolean isComplete(int chunkIndex) {
        Partial partial = open.get(chunkIndex);
        return partial != null && partial.received.covers(0, inventory.chunk(chunkIndex).length());
    }

    public synchronized long stagedBytes(int chunkIndex) {
        Partial partial = open.get(chunkIndex);
        return partial == null ? 0L : partial.received.coveredBytes();
    }

    /**
     * Hash and commit the chunk if every byte has landed.
     *
     * @return where the chunk now lives, or empty if bytes are still missing
     * @throws VerificationFailed if the assembled bytes do not match the manifest hash
     */
    public synchronized Optional<Path> commitIfComplete(int chunkIndex) throws IOException {
        ChunkEntry chunk = inventory.chunk(chunkIndex);
        Partial partial = open.get(chunkIndex);
        if (partial == null || !partial.received.covers(0, chunk.length())) {
            return Optional.empty();
        }

        partial.channel.force(true);
        partial.channel.close();
        open.remove(chunkIndex);
        try {
            // putVerifiedFile consumes the staging file either way, so a rejected chunk
            // leaves no bytes on disk to be retried or accidentally served.
            return Optional.of(store.putVerifiedFile(chunk.sha256(), partial.file));
        } catch (IllegalArgumentException e) {
            bumpEpoch(chunkIndex);
            throw new VerificationFailed(chunkIndex, chunk.sha256(), e);
        } catch (IOException e) {
            bumpEpoch(chunkIndex);
            throw e;
        }
    }

    /** Forget a chunk in progress, for example after a peer disconnects mid-block. */
    public synchronized void discard(int chunkIndex) throws IOException {
        Partial partial = open.remove(chunkIndex);
        if (partial != null) {
            partial.close();
        }
        bumpEpoch(chunkIndex);
    }

    private void bumpEpoch(int chunkIndex) {
        epoch.merge(chunkIndex, 1, Integer::sum);
    }

    @Override
    public synchronized void close() throws IOException {
        IOException failure = null;
        for (Partial partial : open.values()) {
            try {
                partial.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        open.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private Partial openPartial(int chunkIndex) throws IOException {
        Partial existing = open.get(chunkIndex);
        if (existing != null) {
            return existing;
        }
        Path file = stagingDir.resolve(chunkIndex + SUFFIX);
        FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Partial partial = new Partial(file, channel);
        open.put(chunkIndex, partial);
        return partial;
    }

    private void deleteStaleStaging() throws IOException {
        try (Stream<Path> entries = Files.list(stagingDir)) {
            for (Path entry : entries.toList()) {
                if (entry.getFileName().toString().endsWith(SUFFIX)) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private record Partial(Path file, FileChannel channel, ByteRanges received) {

        Partial(Path file, FileChannel channel) {
            this(file, channel, new ByteRanges());
        }

        void close() throws IOException {
            try {
                channel.close();
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    /** The assembled bytes did not hash to what the signed manifest promised. */
    public static final class VerificationFailed extends RuntimeException {

        private final int chunkIndex;
        private final String expectedSha256;

        VerificationFailed(int chunkIndex, String expectedSha256, Throwable cause) {
            super("chunk " + chunkIndex + " failed verification against " + expectedSha256, cause);
            this.chunkIndex = chunkIndex;
            this.expectedSha256 = expectedSha256;
        }

        public int chunkIndex() {
            return chunkIndex;
        }

        public String expectedSha256() {
            return expectedSha256;
        }
    }
}
