package com.prabin.swarmedge.manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Builds a ReleaseManifest from ingest catalog + publisher metadata.
 * Signature is left empty; Ed25519 fill-in is the next Phase 1 step.
 * CanonicalManifest.assetId still works because it ignores signature.
 */
public final class ReleaseManifestFactory {

    private ReleaseManifestFactory() {
    }

    public static ReleaseManifest unsigned(
            String productId,
            String version,
            Path file,
            long chunkSize,
            List<ChunkEntry> chunks,
            String createdAt,
            String expiresAt,
            long sequence,
            String signingKeyId) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(chunks, "chunks");
        long fileSize = Files.size(file);
        long covered = 0;
        for (ChunkEntry chunk : chunks) {
            covered = Math.addExact(covered, chunk.length());
        }
        if (covered != fileSize) {
            throw new ManifestValidationException("chunks do not cover fileSize exactly");
        }
        return new ReleaseManifest(
                ManifestValidator.SCHEMA_VERSION,
                productId,
                version,
                file.getFileName().toString(),
                fileSize,
                new ChunkingSpec(ManifestValidator.MODE_FIXED, chunkSize),
                List.copyOf(chunks),
                createdAt,
                expiresAt,
                sequence,
                signingKeyId,
                ""
        );
    }
}
