package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssetIngestorTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyFileWritesNothing() throws Exception {
        Path file = tempDir.resolve("empty.bin");
        Files.write(file, new byte[0]);
        ChunkStore store = new ChunkStore(tempDir.resolve("store"));

        List<ChunkEntry> chunks = new AssetIngestor(new FileChunker(4), store).ingest(file);

        assertThat(chunks).isEmpty();
        try (var paths = Files.list(tempDir.resolve("store").resolve("chunks"))) {
            assertThat(paths).isEmpty();
        }
    }

    @Test
    void leftoverChunksAreStoredUnderTheirHashes() throws Exception {
        byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Path file = tempDir.resolve("ten.bin");
        Files.write(file, data);
        ChunkStore store = new ChunkStore(tempDir.resolve("store"));

        List<ChunkEntry> chunks = new AssetIngestor(new FileChunker(4), store).ingest(file);

        assertThat(chunks).hasSize(3);
        assertThat(store.read(chunks.get(0).sha256()).orElseThrow()).containsExactly(0, 1, 2, 3);
        assertThat(store.read(chunks.get(1).sha256()).orElseThrow()).containsExactly(4, 5, 6, 7);
        assertThat(store.read(chunks.get(2).sha256()).orElseThrow()).containsExactly(8, 9);
    }

    @Test
    void aSecondVersionReusesTheChunkThatTheEditDidNotTouch() throws Exception {
        byte[] original = new byte[800];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i * 5 + 2);
        }
        Path v1 = tempDir.resolve("v1.bin");
        Files.write(v1, original);
        ChunkStore store = new ChunkStore(tempDir.resolve("store"));
        FastCdcChunker chunker = new FastCdcChunker(32, 64, 256);
        List<ChunkEntry> first = new AssetIngestor(chunker, store).ingest(v1);
        assertThat(first.size()).isGreaterThan(1);

        int insertAt = (int) first.get(0).length();
        Path v2 = tempDir.resolve("v2.bin");
        Files.write(v2, VersionMutator.insert(original, insertAt, new byte[] {1, 2, 3, 4}));
        List<ChunkEntry> second = chunker.chunk(v2);

        assertThat(second.get(0).sha256()).isEqualTo(first.get(0).sha256());
        assertThat(store.contains(second.get(0).sha256())).isTrue();
        int before = storeFileCount(store);
        new AssetIngestor(chunker, store).ingest(v2);
        assertThat(storeFileCount(store)).isLessThan(before + second.size());
    }

    private static int storeFileCount(ChunkStore store) throws Exception {
        try (var paths = Files.list(store.chunksDirectory())) {
            return (int) paths.count();
        }
    }
}
