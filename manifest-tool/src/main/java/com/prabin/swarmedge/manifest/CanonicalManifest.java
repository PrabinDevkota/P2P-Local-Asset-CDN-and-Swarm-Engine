package com.prabin.swarmedge.manifest;

import com.prabin.swarmedge.common.id.AssetId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Canonical unsigned JSON (compact, fixed key order) used for assetId and later Ed25519.
 * Signature field is excluded. All other manifest fields are included so freshness is signed.
 */
public final class CanonicalManifest {

    private CanonicalManifest() {
    }

    public static String unsignedJson(ReleaseManifest manifest) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "schemaVersion").append(manifest.schemaVersion()).append(',');
        field(sb, "productId").append(quote(manifest.productId())).append(',');
        field(sb, "version").append(quote(manifest.version())).append(',');
        field(sb, "fileName").append(quote(manifest.fileName())).append(',');
        field(sb, "fileSize").append(manifest.fileSize()).append(',');
        field(sb, "chunking").append('{');
        field(sb, "mode").append(quote(manifest.chunking().mode())).append(',');
        field(sb, "chunkSize").append(manifest.chunking().chunkSize());
        if (manifest.chunking().fastCdc()) {
            sb.append(',');
            field(sb, "minSize").append(manifest.chunking().minSize()).append(',');
            field(sb, "maxSize").append(manifest.chunking().maxSize());
        }
        sb.append("},");
        field(sb, "chunks").append('[');
        List<ChunkEntry> chunks = manifest.chunks();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkEntry chunk = chunks.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            field(sb, "index").append(chunk.index()).append(',');
            field(sb, "offset").append(chunk.offset()).append(',');
            field(sb, "length").append(chunk.length()).append(',');
            field(sb, "sha256").append(quote(chunk.sha256()));
            sb.append('}');
        }
        sb.append("],");
        field(sb, "createdAt").append(quote(manifest.createdAt())).append(',');
        field(sb, "expiresAt").append(quote(manifest.expiresAt())).append(',');
        field(sb, "sequence").append(manifest.sequence()).append(',');
        field(sb, "signingKeyId").append(quote(manifest.signingKeyId()));
        sb.append('}');
        return sb.toString();
    }

    public static AssetId assetId(ReleaseManifest manifest) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(unsignedJson(manifest).getBytes(StandardCharsets.UTF_8));
            return AssetId.of(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StringBuilder field(StringBuilder sb, String name) {
        return sb.append(quote(name)).append(':');
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
