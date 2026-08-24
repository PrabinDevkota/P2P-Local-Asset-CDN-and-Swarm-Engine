package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetMaterializerTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyCatalogWritesEmptyFile() throws Exception {
        ChunkStore store = new ChunkStore(tempDir.resolve("store"));
        Path dest = tempDir.resolve("out.bin");

        new AssetMaterializer(store).materialize(List.of(), dest);

        assertThat(Files.readAllBytes(dest)).isEmpty();
    }

    @Test
    void ingestThenMaterializeRestoresOriginalBytes() throws Exception {
        byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Path source = tempDir.resolve("ten.bin");
        Files.write(source, data);
        ChunkStore store = new ChunkStore(tempDir.resolve("store"));
        List<ChunkEntry> chunks = new AssetIngestor(new FileChunker(4), store).ingest(source);

        Path dest = tempDir.resolve("out.bin");
        new AssetMaterializer(store).materialize(chunks, dest);

        assertThat(Files.readAllBytes(dest)).containsExactly(data);
    }

    @Test
    void missingChunkDoesNotReplaceDestination() throws Exception {
        byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Path source = tempDir.resolve("ten.bin");
        Files.write(source, data);
        List<ChunkEntry> chunks = new FileChunker(4).chunk(source);
        ChunkStore store = new ChunkStore(tempDir.resolve("store"));
        Path dest = tempDir.resolve("out.bin");
        Files.write(dest, new byte[] {9});

        assertThatThrownBy(() -> new AssetMaterializer(store).materialize(chunks, dest))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("missing chunk");
        assertThat(Files.readAllBytes(dest)).containsExactly(9);
    }
}
