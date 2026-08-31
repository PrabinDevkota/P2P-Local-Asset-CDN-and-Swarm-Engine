package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestVerifierTest {

    @TempDir
    Path tempDir;

    @Test
    void matchingPublicKeyAcceptsSignedManifest() throws Exception {
        KeyPair keys = ed25519();
        ReleaseManifest signed = ManifestSigner.sign(unsignedTenByteManifest(), keys.getPrivate());

        assertThatCode(() -> ManifestVerifier.verify(signed, keys.getPublic()))
                .doesNotThrowAnyException();
    }

    @Test
    void wrongPublicKeyIsRejected() throws Exception {
        KeyPair publisher = ed25519();
        KeyPair impostor = ed25519();
        ReleaseManifest signed = ManifestSigner.sign(unsignedTenByteManifest(), publisher.getPrivate());

        assertThatThrownBy(() -> ManifestVerifier.verify(signed, impostor.getPublic()))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("invalid Ed25519 signature");
    }

    @Test
    void tamperedFieldWithOriginalStampIsRejected() throws Exception {
        KeyPair keys = ed25519();
        ReleaseManifest signed = ManifestSigner.sign(unsignedTenByteManifest(), keys.getPrivate());
        ReleaseManifest tampered = new ReleaseManifest(
                signed.schemaVersion(),
                signed.productId(),
                "9.9.9",
                signed.fileName(),
                signed.fileSize(),
                signed.chunking(),
                signed.chunks(),
                signed.createdAt(),
                signed.expiresAt(),
                signed.sequence(),
                signed.signingKeyId(),
                signed.signature()
        );

        assertThatThrownBy(() -> ManifestVerifier.verify(tampered, keys.getPublic()))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("invalid Ed25519 signature");
    }

    @Test
    void garbageSignatureIsRejected() throws Exception {
        KeyPair keys = ed25519();
        ReleaseManifest signed = ManifestSigner.sign(unsignedTenByteManifest(), keys.getPrivate());
        ReleaseManifest garbage = withSignature(signed, "not-valid-base64!!!");

        assertThatThrownBy(() -> ManifestVerifier.verify(garbage, keys.getPublic()))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void shortPlaceholderSignatureIsRejected() throws Exception {
        KeyPair keys = ed25519();
        ReleaseManifest signed = ManifestSigner.sign(unsignedTenByteManifest(), keys.getPrivate());
        ReleaseManifest placeholder = withSignature(signed, Base64.getEncoder().encodeToString("testsig".getBytes()));

        assertThatThrownBy(() -> ManifestVerifier.verify(placeholder, keys.getPublic()))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("64 bytes");
    }

    @Test
    void unsignedManifestIsRejected() throws Exception {
        KeyPair keys = ed25519();

        assertThatThrownBy(() -> ManifestVerifier.verify(unsignedTenByteManifest(), keys.getPublic()))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("signature required");
    }

    private ReleaseManifest unsignedTenByteManifest() throws Exception {
        Path file = tempDir.resolve("ten.bin");
        Files.write(file, new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        List<ChunkEntry> chunks = new FileChunker(4).chunk(file);
        return ReleaseManifestFactory.unsigned(
                "game-x", "1.4.0", file, 4, chunks,
                "2026-08-12T00:00:00Z", "2026-09-12T00:00:00Z", 1, "release-key-2026-01");
    }

    private static ReleaseManifest withSignature(ReleaseManifest manifest, String signature) {
        return new ReleaseManifest(
                manifest.schemaVersion(),
                manifest.productId(),
                manifest.version(),
                manifest.fileName(),
                manifest.fileSize(),
                manifest.chunking(),
                manifest.chunks(),
                manifest.createdAt(),
                manifest.expiresAt(),
                manifest.sequence(),
                manifest.signingKeyId(),
                signature
        );
    }

    private static KeyPair ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
