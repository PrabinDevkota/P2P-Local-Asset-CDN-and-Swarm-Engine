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
}
