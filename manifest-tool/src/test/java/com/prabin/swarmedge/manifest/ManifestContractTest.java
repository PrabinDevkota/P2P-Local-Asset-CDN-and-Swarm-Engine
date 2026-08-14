package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestContractTest {

    private static final String FIXTURE = """
            {"schemaVersion":1,"productId":"game-x","version":"1.4.0","fileName":"game-x-1.4.0.bin","fileSize":8,"chunking":{"mode":"FIXED","chunkSize":4194304},"chunks":[{"index":0,"offset":0,"length":8,"sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}],"createdAt":"2026-08-12T00:00:00Z","expiresAt":"2026-09-12T00:00:00Z","sequence":17,"signingKeyId":"release-key-2026-01","signature":"dGVzdHNpZw=="}""";

    @Test
    void roundTripIsByteStable() throws Exception {
        String expected = new String(
                getClass().getResourceAsStream("/fixtures/manifest-v1-valid.json").readAllBytes(),
                StandardCharsets.UTF_8
        ).trim();
        ReleaseManifest parsed = ManifestJson.parse(expected);
        assertThat(ManifestJson.toJson(parsed)).isEqualTo(expected);
        assertThat(expected).isEqualTo(FIXTURE);
        assertThat(CanonicalManifest.assetId(parsed).toHex()).hasSize(64);
    }

    @Test
    void rejectsOverlapAndBadHash() {
        ReleaseManifest overlap = new ReleaseManifest(
                1, "p", "1", "f.bin", 16,
                new ChunkingSpec("FIXED", 8),
                List.of(
                        new ChunkEntry(0, 0, 8, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                        new ChunkEntry(1, 4, 8, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                ),
                "2026-08-12T00:00:00Z", "2026-09-12T00:00:00Z", 1, "k", "sig"
        );
        assertThatThrownBy(() -> ManifestValidator.validate(overlap))
                .isInstanceOf(ManifestValidationException.class);

        ReleaseManifest badHash = new ReleaseManifest(
                1, "p", "1", "f.bin", 8,
                new ChunkingSpec("FIXED", 4194304),
                List.of(new ChunkEntry(0, 0, 8, "ZZ")),
                "2026-08-12T00:00:00Z", "2026-09-12T00:00:00Z", 1, "k", "sig"
        );
        assertThatThrownBy(() -> ManifestValidator.validate(badHash))
                .isInstanceOf(ManifestValidationException.class);
    }

    @Test
    void rejectsWrongFileSizeAndNegativeOffset() {
        ReleaseManifest wrongSize = new ReleaseManifest(
                1, "p", "1", "f.bin", 99,
                new ChunkingSpec("FIXED", 4194304),
                List.of(new ChunkEntry(0, 0, 8, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")),
                "2026-08-12T00:00:00Z", "2026-09-12T00:00:00Z", 1, "k", "sig"
        );
        assertThatThrownBy(() -> ManifestValidator.validate(wrongSize))
                .isInstanceOf(ManifestValidationException.class);

        ReleaseManifest negative = new ReleaseManifest(
                1, "p", "1", "f.bin", 8,
                new ChunkingSpec("FIXED", 4194304),
                List.of(new ChunkEntry(0, -1, 8, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")),
                "2026-08-12T00:00:00Z", "2026-09-12T00:00:00Z", 1, "k", "sig"
        );
        assertThatThrownBy(() -> ManifestValidator.validate(negative))
                .isInstanceOf(ManifestValidationException.class);
    }
}
