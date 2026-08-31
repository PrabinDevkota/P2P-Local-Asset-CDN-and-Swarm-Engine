package com.prabin.swarmedge.manifest;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * Leecher-side stamp check: Ed25519 over CanonicalManifest.unsignedJson.
 * The caller must supply a trusted public key; this class does not look up keys.
 * Fail closed: bad encoding, wrong key, or tampered fields throw.
 */
public final class ManifestVerifier {

    private static final int ED25519_SIGNATURE_BYTES = 64;

    private ManifestVerifier() {
    }

    public static void verify(ReleaseManifest signed, PublicKey publicKey) {
        Objects.requireNonNull(signed, "signed");
        Objects.requireNonNull(publicKey, "publicKey");
        ManifestValidator.validate(signed);
        byte[] signatureBytes = decodeSignature(signed.signature());
        byte[] payload = CanonicalManifest.unsignedJson(signed).getBytes(StandardCharsets.UTF_8);
        if (!ed25519Verify(publicKey, payload, signatureBytes)) {
            throw new ManifestValidationException("invalid Ed25519 signature");
        }
    }

    private static byte[] decodeSignature(String signature) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            throw new ManifestValidationException("signature is not valid Base64");
        }
        if (bytes.length != ED25519_SIGNATURE_BYTES) {
            throw new ManifestValidationException("Ed25519 signature must be 64 bytes");
        }
        return bytes;
    }

    private static boolean ed25519Verify(PublicKey publicKey, byte[] payload, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(payload);
            return signature.verify(signatureBytes);
        } catch (GeneralSecurityException e) {
            throw new ManifestValidationException("Ed25519 verify failed");
        }
    }
}
