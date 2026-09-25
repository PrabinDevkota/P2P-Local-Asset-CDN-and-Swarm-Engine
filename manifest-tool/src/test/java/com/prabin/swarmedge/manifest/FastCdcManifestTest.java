package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FastCdcManifestTest {

    private static final String HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @TempDir
    Path tempDir;

    @Test
    void fixedCanonicalJsonStaysTwoChunkingFields() {
        ReleaseManifest manifest = sample(new ChunkingSpec("FIXED", 8), 8);
        String json = CanonicalManifest.unsignedJson(manifest);
        assertThat(json).contains("\"chunking\":{\"mode\":\"FIXED\",\"chunkSize\":8}");
        assertThat(json).doesNotContain("minSize");
    }

    @Test
    void fastCdcCanonicalJsonRoundTrips() throws Exception {
        byte[] body = new byte[400];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 3 + 1);
        }
        Path file = tempDir.resolve("cdc.bin");
        Files.write(file, body);
        Chunker chunker = new FastCdcChunker(16, 64, 128);
        List<ChunkEntry> chunks = chunker.chunk(file);
        ReleaseManifest unsigned = ReleaseManifestFactory.unsigned(
                "game-x", "1.4.0", file, chunker.spec(), chunks,
                "2026-09-25T00:00:00Z", "2026-10-25T00:00:00Z", 1, "release-key");
        String signed = ManifestJson.toJson(new ReleaseManifest(
                unsigned.schemaVersion(), unsigned.productId(), unsigned.version(), unsigned.fileName(),
                unsigned.fileSize(), unsigned.chunking(), unsigned.chunks(),
                unsigned.createdAt(), unsigned.expiresAt(), unsigned.sequence(),
                unsigned.signingKeyId(), "c2ln"));

        ReleaseManifest parsed = ManifestJson.parse(signed);

        assertThat(parsed.chunking()).isEqualTo(chunker.spec());
        assertThat(CanonicalManifest.unsignedJson(parsed)).contains("\"minSize\":16", "\"maxSize\":128");
        assertThat(parsed.chunks()).isEqualTo(chunks);
    }

    @Test
    void fastCdcRejectsAShortMiddleChunkAndAFixedSpecWithBounds() {
        ReleaseManifest shortMiddle = sample(new ChunkingSpec("FASTCDC", 64, 32, 128),
                new ChunkEntry(0, 0, 8, HASH),
                new ChunkEntry(1, 8, 64, HASH));
        assertThatThrownBy(() -> ManifestValidator.validate(shortMiddle))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("minSize");

        ReleaseManifest fixedWithBounds = sample(new ChunkingSpec("FIXED", 8, 4, 8), 8);
        assertThatThrownBy(() -> ManifestValidator.validate(fixedWithBounds))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("FIXED");
    }

    private static ReleaseManifest sample(ChunkingSpec spec, long fileSize) {
        return sample(spec, new ChunkEntry(0, 0, fileSize, HASH));
    }

    private static ReleaseManifest sample(ChunkingSpec spec, ChunkEntry... chunks) {
        long size = 0;
        for (ChunkEntry chunk : chunks) {
            size += chunk.length();
        }
        return new ReleaseManifest(
                1, "p", "1", "f.bin", size, spec, List.of(chunks),
                "2026-09-25T00:00:00Z", "2026-10-25T00:00:00Z", 1, "k", "sig");
    }
}
