package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestSignerTest {

    @TempDir
    Path tempDir;

    @Test
    void signFillsBase64SignatureAndKeepsAssetId() throws Exception {
        ReleaseManifest unsigned = unsignedTenByteManifest();
        KeyPair keys = ed25519();

        ReleaseManifest signed = ManifestSigner.sign(unsigned, keys.getPrivate());

        assertThat(signed.signature()).isNotBlank();
        assertThat(Base64.getDecoder().decode(signed.signature())).hasSize(64);
        assertThat(signed.chunks()).isEqualTo(unsigned.chunks());
        assertThat(CanonicalManifest.assetId(signed)).isEqualTo(CanonicalManifest.assetId(unsigned));
        ManifestValidator.validate(signed);
    }

    @Test
    void sameKeyAndManifestProduceTheSameSignature() throws Exception {
        ReleaseManifest unsigned = unsignedTenByteManifest();
        KeyPair keys = ed25519();

        ReleaseManifest first = ManifestSigner.sign(unsigned, keys.getPrivate());
        ReleaseManifest second = ManifestSigner.sign(unsigned, keys.getPrivate());

        assertThat(second.signature()).isEqualTo(first.signature());
    }

    @Test
    void rejectsNonEd25519Key() throws Exception {
        ReleaseManifest unsigned = unsignedTenByteManifest();
        KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        assertThatThrownBy(() -> ManifestSigner.sign(unsigned, rsa.getPrivate()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ed25519");
    }

    private ReleaseManifest unsignedTenByteManifest() throws Exception {
        Path file = tempDir.resolve("ten.bin");
        Files.write(file, new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        List<ChunkEntry> chunks = new FileChunker(4).chunk(file);
        return ReleaseManifestFactory.unsigned(
                "game-x", "1.4.0", file, 4, chunks,
                "2026-08-12T00:00:00Z", "2026-09-12T00:00:00Z", 1, "release-key-2026-01");
    }

    private static KeyPair ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
