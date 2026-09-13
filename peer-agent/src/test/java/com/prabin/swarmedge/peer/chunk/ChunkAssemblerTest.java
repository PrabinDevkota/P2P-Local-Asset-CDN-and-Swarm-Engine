package com.prabin.swarmedge.peer.chunk;

import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4-03: bytes must land at the right offset, and a chunk becomes trusted only after
 * its own hash says so. Nothing here is allowed to hold a whole chunk in heap.
 */
class ChunkAssemblerTest {

    private static final int CHUNK_SIZE = 512;
    private static final int BLOCK_SIZE = 64;
    private static final int TAIL_BYTES = 100;
    private static final int FULL_CHUNKS = 3;

    @TempDir
    Path tempDir;

    private byte[] original;
    private ReleaseManifest manifest;
    private Path dataRoot;
    private Path stagingDir;
    private ChunkStore store;
    private ChunkInventory inventory;

    @BeforeEach
    void publishAsset() throws Exception {
        original = deterministicBytes(FULL_CHUNKS * CHUNK_SIZE + TAIL_BYTES);
        Path published = Files.write(tempDir.resolve("game-x-1.4.0.bin"), original);
        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        manifest = ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                "2026-09-13T00:00:00Z", "2026-10-13T00:00:00Z", 1, "release-key-2026-01");
        dataRoot = tempDir.resolve("data");
        stagingDir = dataRoot.resolve("staging").resolve("asset");
        store = new ChunkStore(dataRoot);
        inventory = new ChunkInventory(manifest, store);
    }

    @Test
    void aChunkIsCommittedOnlyAfterTheLastByteLands() throws Exception {
        try (ChunkAssembler assembler = assembler()) {
            List<long[]> blocks = blocksOf(0);
            for (int i = 0; i < blocks.size() - 1; i++) {
                feed(assembler, 0, blocks.get(i));
                assertThat(assembler.commitIfComplete(0)).isEmpty();
                assertThat(assembler.isComplete(0)).isFalse();
            }
            feed(assembler, 0, blocks.getLast());

            assertThat(assembler.isComplete(0)).isTrue();
            Optional<Path> stored = assembler.commitIfComplete(0);

            assertThat(stored).contains(store.pathFor(chunk(0).sha256()));
            assertThat(store.read(chunk(0).sha256()).orElseThrow()).containsExactly(chunkBytes(0));
        }
    }

    @Test
    void blocksArrivingOutOfOrderStillRebuildTheChunk() throws Exception {
        try (ChunkAssembler assembler = assembler()) {
            List<long[]> blocks = new ArrayList<>(blocksOf(1));
            Collections.shuffle(blocks, new Random(7));

            for (long[] block : blocks) {
                feed(assembler, 1, block);
            }

            assertThat(assembler.commitIfComplete(1)).isPresent();
            assertThat(store.read(chunk(1).sha256()).orElseThrow()).containsExactly(chunkBytes(1));
        }
    }

    @Test
    void aBlockSplitIntoSocketSizedPiecesIsWrittenAtTheRightOffsets() throws Exception {
        try (ChunkAssembler assembler = assembler()) {
            byte[] expected = chunkBytes(0);
            // Deliberately uneven pieces: TCP does not respect block boundaries.
            int[] pieces = {1, 7, 13, 100, 391};
            long offset = 0;
            for (int piece : pieces) {
                assembler.accept(0, offset, expected, (int) offset, piece);
                offset += piece;
            }

            assertThat(offset).isEqualTo(CHUNK_SIZE);
            assertThat(assembler.commitIfComplete(0)).isPresent();
            assertThat(store.read(chunk(0).sha256()).orElseThrow()).containsExactly(expected);
        }
    }

    @Test
    void theShortFinalChunkCommitsAtItsOwnLength() throws Exception {
        try (ChunkAssembler assembler = assembler()) {
            for (long[] block : blocksOf(FULL_CHUNKS)) {
                feed(assembler, FULL_CHUNKS, block);
            }

            assertThat(assembler.commitIfComplete(FULL_CHUNKS)).isPresent();
            assertThat(Files.size(store.pathFor(chunk(FULL_CHUNKS).sha256()))).isEqualTo(TAIL_BYTES);
        }
    }

    @Test
    void tamperedBytesFailClosedAndLeaveNothingToRetryFrom() throws Exception {
        try (ChunkAssembler assembler = assembler()) {
            byte[] tampered = chunkBytes(0);
            tampered[5] ^= 0xFF;
            assembler.accept(0, 0, tampered, 0, tampered.length);

            assertThatThrownBy(() -> assembler.commitIfComplete(0))
                    .isInstanceOf(ChunkAssembler.VerificationFailed.class)
                    .hasMessageContaining("chunk 0 failed verification");

            assertThat(store.contains(chunk(0).sha256())).isFalse();
            assertThat(stagingFiles()).isEmpty();
            assertThat(assembler.stagedBytes(0)).isZero();
        }
    }

    @Test
    void aChunkRejectedOnceCanBeFetchedAgainFromScratch() throws Exception {
        try (ChunkAssembler assembler = assembler()) {
            byte[] tampered = chunkBytes(0);
            tampered[0] ^= 0xFF;
            assembler.accept(0, 0, tampered, 0, tampered.length);
            assertThatThrownBy(() -> assembler.commitIfComplete(0))
                    .isInstanceOf(ChunkAssembler.VerificationFailed.class);

            assembler.accept(0, 0, chunkBytes(0), 0, CHUNK_SIZE);

            assertThat(assembler.commitIfComplete(0)).isPresent();
            assertThat(store.read(chunk(0).sha256()).orElseThrow()).containsExactly(chunkBytes(0));
        }
    }

    @Test
    void aWriteOutsideTheChunkIsRefusedBeforeItTouchesDisk() throws Exception {
        try (ChunkAssembler assembler = assembler()) {
            byte[] data = new byte[BLOCK_SIZE];

            assertThatThrownBy(() -> assembler.accept(0, CHUNK_SIZE - 1, data, 0, BLOCK_SIZE))
                    .isInstanceOf(ProtocolViolationException.class);
            assertThatThrownBy(() -> assembler.accept(99, 0, data, 0, BLOCK_SIZE))
                    .isInstanceOf(ProtocolViolationException.class);
            assertThatThrownBy(() -> assembler.accept(0, -1, data, 0, BLOCK_SIZE))
                    .isInstanceOf(ProtocolViolationException.class);

            assertThat(stagingFiles()).isEmpty();
        }
    }

    @Test
    void aSliceThatDoesNotFitItsArrayIsAProgrammingError() throws Exception {
        try (ChunkAssembler assembler = assembler()) {
            byte[] data = new byte[BLOCK_SIZE];

            assertThatThrownBy(() -> assembler.accept(0, 0, data, 1, BLOCK_SIZE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> assembler.accept(0, 0, data, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aDuplicateBlockIsHarmless() throws Exception {
        try (ChunkAssembler assembler = assembler()) {
            for (long[] block : blocksOf(2)) {
                feed(assembler, 2, block);
                feed(assembler, 2, block);
            }

            assertThat(assembler.stagedBytes(2)).isEqualTo(CHUNK_SIZE);
            assertThat(assembler.commitIfComplete(2)).isPresent();
        }
    }

    @Test
    void discardingAChunkInProgressRemovesItsStagingFile() throws Exception {
        try (ChunkAssembler assembler = assembler()) {
            feed(assembler, 0, blocksOf(0).getFirst());
            assertThat(stagingFiles()).hasSize(1);

            assembler.discard(0);

            assertThat(stagingFiles()).isEmpty();
            assertThat(assembler.stagedBytes(0)).isZero();
            assertThat(assembler.commitIfComplete(0)).isEmpty();
        }
    }

    @Test
    void aRestartKeepsVerifiedChunksAndStartsPartialOnesOver() throws Exception {
        try (ChunkAssembler first = assembler()) {
            for (long[] block : blocksOf(0)) {
                feed(first, 0, block);
            }
            first.commitIfComplete(0);
            // Chunk 1 only half arrives before the process dies.
            feed(first, 1, blocksOf(1).getFirst());
        }

        assertThat(stagingFiles()).isEmpty();

        try (ChunkAssembler second = assembler()) {
            assertThat(new ChunkInventory(manifest, store).missing()).containsExactly(1, 2, 3);
            assertThat(second.stagedBytes(1)).isZero();

            for (long[] block : blocksOf(1)) {
                feed(second, 1, block);
            }

            assertThat(second.commitIfComplete(1)).isPresent();
        }
    }

    @Test
    void aStaleStagingFileFromAnEarlierRunIsNotTrusted() throws Exception {
        Files.createDirectories(stagingDir);
        Files.write(stagingDir.resolve("0.part"), chunkBytes(0));

        try (ChunkAssembler assembler = assembler()) {
            assertThat(stagingFiles()).isEmpty();
            assertThat(assembler.commitIfComplete(0)).isEmpty();
        }
    }

    private ChunkAssembler assembler() throws Exception {
        return new ChunkAssembler(inventory, store, stagingDir);
    }

    private void feed(ChunkAssembler assembler, int chunkIndex, long[] block) throws Exception {
        byte[] data = chunkBytes(chunkIndex);
        assembler.accept(chunkIndex, block[0], data, (int) block[0], (int) block[1]);
    }

    /** Offset and length pairs covering one chunk, the way the leecher would ask for them. */
    private List<long[]> blocksOf(int chunkIndex) {
        long length = chunk(chunkIndex).length();
        List<long[]> blocks = new ArrayList<>();
        for (long offset = 0; offset < length; offset += BLOCK_SIZE) {
            blocks.add(new long[] {offset, Math.min(BLOCK_SIZE, length - offset)});
        }
        return blocks;
    }

    private ChunkEntry chunk(int index) {
        return manifest.chunks().get(index);
    }

    private byte[] chunkBytes(int index) {
        ChunkEntry entry = chunk(index);
        return Arrays.copyOfRange(original, (int) entry.offset(), (int) (entry.offset() + entry.length()));
    }

    private List<Path> stagingFiles() throws Exception {
        if (!Files.isDirectory(stagingDir)) {
            return List.of();
        }
        try (var entries = Files.list(stagingDir)) {
            return entries.toList();
        }
    }

    private static byte[] deterministicBytes(int length) {
        byte[] out = new byte[length];
        new Random(20260913).nextBytes(out);
        return out;
    }
}
