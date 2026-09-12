package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-05: a peer killed mid-ingest restarts and finishes without reprocessing
 * chunks it already verified. Fixed chunking is offset-aligned, so the slices
 * of a truncated download keep the hashes they will have in the full file.
 */
class AssetResumeTest {

    private static final int CHUNK_SIZE = 64;
    private static final int CHUNKS_BEFORE_CRASH = 2;
    private static final int TOTAL_CHUNKS = 5;

    @TempDir
    Path tempDir;

    @Test
    void restartKeepsVerifiedChunksAndRebuildsTheOriginalFile() throws Exception {
        byte[] original = deterministicBytes(TOTAL_CHUNKS * CHUNK_SIZE);
        Path asset = Files.write(tempDir.resolve("game-x-1.4.0.bin"), original);
        Path storeRoot = tempDir.resolve("data");
        Path database = storeRoot.resolve("cache.db");

        List<ChunkEntry> partialChunks;
        try (ChunkIndex index = ChunkIndex.open(database)) {
            Path partial = Files.write(tempDir.resolve("partial.bin"),
                    Arrays.copyOf(original, CHUNKS_BEFORE_CRASH * CHUNK_SIZE));
            partialChunks = ingestor(storeRoot, index).ingest(partial);
        }
        assertThat(partialChunks).hasSize(CHUNKS_BEFORE_CRASH);

        // The process dies here. Age the surviving chunk files so a rewrite would show.
        FileTime before = FileTime.from(Instant.parse("2026-01-01T00:00:00Z"));
        List<Path> survivors = partialChunks.stream()
                .map(chunk -> chunkPath(storeRoot, chunk.sha256()))
                .toList();
        for (Path survivor : survivors) {
            Files.setLastModifiedTime(survivor, before);
        }

        List<ChunkEntry> chunks;
        try (ChunkIndex index = ChunkIndex.open(database)) {
            chunks = ingestor(storeRoot, index).ingest(asset);

            assertThat(chunks).hasSize(TOTAL_CHUNKS);
            assertThat(index.verifiedHashes())
                    .containsExactlyInAnyOrderElementsOf(chunks.stream().map(ChunkEntry::sha256).toList());
            assertThat(index.verifiedBytes()).isEqualTo(original.length);

            Path rebuilt = tempDir.resolve("rebuilt.bin");
            new AssetMaterializer(new ChunkStore(storeRoot, index)).materialize(chunks, rebuilt);
            assertThat(Files.readAllBytes(rebuilt)).containsExactly(original);
        }

        for (Path survivor : survivors) {
            assertThat(Files.getLastModifiedTime(survivor))
                    .as("chunk already verified before the crash must not be rewritten")
                    .isEqualTo(before);
        }
        assertThat(chunks.subList(0, CHUNKS_BEFORE_CRASH))
                .extracting(ChunkEntry::sha256)
                .containsExactlyElementsOf(partialChunks.stream().map(ChunkEntry::sha256).toList());
        assertThat(tempFilesUnder(storeRoot)).isEmpty();
    }

    private static AssetIngestor ingestor(Path storeRoot, ChunkIndex index) throws Exception {
        return new AssetIngestor(new FileChunker(CHUNK_SIZE), new ChunkStore(storeRoot, index));
    }

    private static Path chunkPath(Path storeRoot, String hash) {
        return storeRoot.resolve("chunks")
                .resolve(hash.substring(0, 2))
                .resolve(hash.substring(2, 4))
                .resolve(hash + ".chunk");
    }

    private static List<Path> tempFilesUnder(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".tmp")).toList();
        }
    }

    private static byte[] deterministicBytes(int size) {
        byte[] data = new byte[size];
        new Random(20260912L).nextBytes(data);
        return data;
    }
}
