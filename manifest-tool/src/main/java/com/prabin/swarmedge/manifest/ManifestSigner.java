package com.prabin.swarmedge.manifest;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * Publisher stamp: Ed25519 over CanonicalManifest.unsignedJson bytes.
 * Signature is stored as standard Base64 on the ReleaseManifest.
 */
public final class ManifestSigner {

    private ManifestSigner() {
    }

    public static ReleaseManifest sign(ReleaseManifest unsigned, PrivateKey privateKey) {
        Objects.requireNonNull(unsigned, "unsigned");
        Objects.requireNonNull(privateKey, "privateKey");
        byte[] payload = CanonicalManifest.unsignedJson(unsigned).getBytes(StandardCharsets.UTF_8);
        String signature = Base64.getEncoder().encodeToString(ed25519Sign(privateKey, payload));
        return new ReleaseManifest(
                unsigned.schemaVersion(),
                unsigned.productId(),
                unsigned.version(),
                unsigned.fileName(),
                unsigned.fileSize(),
                unsigned.chunking(),
                unsigned.chunks(),
                unsigned.createdAt(),
                unsigned.expiresAt(),
                unsigned.sequence(),
                unsigned.signingKeyId(),
                signature
        );
    }

    private static byte[] ed25519Sign(PrivateKey privateKey, byte[] payload) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(payload);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Ed25519 sign failed", e);
        }
    }
}
