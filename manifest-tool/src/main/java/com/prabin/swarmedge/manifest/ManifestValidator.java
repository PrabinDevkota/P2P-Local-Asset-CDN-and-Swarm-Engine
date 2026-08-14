package com.prabin.swarmedge.manifest;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ManifestValidator {

    public static final int SCHEMA_VERSION = 1;
    public static final String MODE_FIXED = "FIXED";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern INSTANT = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");

    private ManifestValidator() {
    }

    public static void validate(ReleaseManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        if (manifest.schemaVersion() != SCHEMA_VERSION) {
            throw new ManifestValidationException("schemaVersion must be " + SCHEMA_VERSION);
        }
        requireText(manifest.productId(), "productId");
        requireText(manifest.version(), "version");
        requireText(manifest.fileName(), "fileName");
        if (manifest.fileSize() < 0) {
            throw new ManifestValidationException("fileSize must be non-negative");
        }
        ChunkingSpec chunking = manifest.chunking();
        if (chunking == null || !MODE_FIXED.equals(chunking.mode())) {
            throw new ManifestValidationException("chunking.mode must be FIXED");
        }
        if (chunking.chunkSize() <= 0) {
            throw new ManifestValidationException("chunkSize must be positive");
        }
        List<ChunkEntry> chunks = manifest.chunks();
        if (chunks == null || chunks.isEmpty()) {
            if (manifest.fileSize() != 0) {
                throw new ManifestValidationException("chunks must cover fileSize");
            }
            validateFreshness(manifest);
            return;
        }
        long cursor = 0;
        for (int i = 0; i < chunks.size(); i++) {
            ChunkEntry chunk = chunks.get(i);
            if (chunk.index() != i) {
                throw new ManifestValidationException("chunk index must be sequential from 0");
            }
            if (chunk.offset() < 0 || chunk.length() <= 0) {
                throw new ManifestValidationException("chunk offset/length invalid");
            }
            if (chunk.offset() != cursor) {
                throw new ManifestValidationException("chunks must be contiguous (gap or overlap)");
            }
            boolean last = i == chunks.size() - 1;
            if (!last && chunk.length() != chunking.chunkSize()) {
                throw new ManifestValidationException("non-final chunk must equal chunkSize");
            }
            if (last && chunk.length() > chunking.chunkSize()) {
                throw new ManifestValidationException("final chunk longer than chunkSize");
            }
            if (chunk.sha256() == null || !SHA256.matcher(chunk.sha256()).matches()) {
                throw new ManifestValidationException("sha256 must be 64 lowercase hex characters");
            }
            cursor = Math.addExact(cursor, chunk.length());
        }
        if (cursor != manifest.fileSize()) {
            throw new ManifestValidationException("chunks do not cover fileSize exactly");
        }
        validateFreshness(manifest);
    }

    private static void validateFreshness(ReleaseManifest manifest) {
        if (manifest.createdAt() == null || !INSTANT.matcher(manifest.createdAt()).matches()) {
            throw new ManifestValidationException("createdAt must be UTC instant YYYY-MM-DDTHH:MM:SSZ");
        }
        if (manifest.expiresAt() == null || !INSTANT.matcher(manifest.expiresAt()).matches()) {
            throw new ManifestValidationException("expiresAt must be UTC instant YYYY-MM-DDTHH:MM:SSZ");
        }
        if (manifest.sequence() < 0) {
            throw new ManifestValidationException("sequence must be non-negative");
        }
        requireText(manifest.signingKeyId(), "signingKeyId");
        if (manifest.signature() == null || manifest.signature().isBlank()) {
            throw new ManifestValidationException("signature required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ManifestValidationException(field + " required");
        }
    }
}
