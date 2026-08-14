package com.prabin.swarmedge.manifest;

import java.util.List;

public record ReleaseManifest(
        int schemaVersion,
        String productId,
        String version,
        String fileName,
        long fileSize,
        ChunkingSpec chunking,
        List<ChunkEntry> chunks,
        String createdAt,
        String expiresAt,
        long sequence,
        String signingKeyId,
        String signature
) {
}
