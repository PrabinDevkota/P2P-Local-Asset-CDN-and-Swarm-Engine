package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseManifestFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void unsignedManifestUsesFileNameSizeAndIngestChunks() throws Exception {
        byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Path file = tempDir.resolve("ten.bin");
        Files.write(file, data);
        List<ChunkEntry> chunks = new FileChunker(4).chunk(file);

        ReleaseManifest manifest = ReleaseManifestFactory.unsigned(
                "game-x", "1.4.0", file, 4, chunks,
                "2026-08-12T00:00:00Z", "2026-09-12T00:00:00Z", 1, "release-key-2026-01");

        assertThat(manifest.fileName()).isEqualTo("ten.bin");
        assertThat(manifest.fileSize()).isEqualTo(10);
        assertThat(manifest.chunking()).isEqualTo(new ChunkingSpec("FIXED", 4));
        assertThat(manifest.chunks()).isEqualTo(chunks);
        assertThat(manifest.signature()).isEmpty();
        assertThat(CanonicalManifest.assetId(manifest).toHex()).hasSize(64);
    }

    @Test
    void emptyFileHasNoChunks() throws Exception {
        Path file = tempDir.resolve("empty.bin");
        Files.write(file, new byte[0]);

        ReleaseManifest manifest = ReleaseManifestFactory.unsigned(
                "p", "1", file, 4, List.of(),
                "2026-08-12T00:00:00Z", "2026-09-12T00:00:00Z", 0, "k");

        assertThat(manifest.fileSize()).isZero();
        assertThat(manifest.chunks()).isEmpty();
    }

    @Test
    void rejectsChunksThatDoNotCoverTheFile() throws Exception {
        Path file = tempDir.resolve("ten.bin");
        Files.write(file, new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

        assertThatThrownBy(() -> ReleaseManifestFactory.unsigned(
                "p", "1", file, 4, List.of(),
                "2026-08-12T00:00:00Z", "2026-09-12T00:00:00Z", 0, "k"))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("cover fileSize");
    }
}
