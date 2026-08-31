package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ed25519KeysTest {

    @TempDir
    Path tempDir;

    @Test
    void pemRoundTripPreservesKeyMaterial() throws Exception {
        KeyPair keys = Ed25519Keys.generate();
        Path privatePem = tempDir.resolve("private.pem");
        Path publicPem = tempDir.resolve("public.pem");

        Ed25519Keys.writePrivateKey(privatePem, keys.getPrivate());
        Ed25519Keys.writePublicKey(publicPem, keys.getPublic());

        assertThat(Files.readString(privatePem)).contains("BEGIN PRIVATE KEY");
        assertThat(Files.readString(publicPem)).contains("BEGIN PUBLIC KEY");
        assertThat(Ed25519Keys.readPrivateKey(privatePem).getEncoded())
                .containsExactly(keys.getPrivate().getEncoded());
        assertThat(Ed25519Keys.readPublicKey(publicPem).getEncoded())
                .containsExactly(keys.getPublic().getEncoded());
    }

    @Test
    void reloadedKeysCanSignAndVerify() throws Exception {
        KeyPair keys = Ed25519Keys.generate();
        Path privatePem = tempDir.resolve("private.pem");
        Path publicPem = tempDir.resolve("public.pem");
        Ed25519Keys.writePrivateKey(privatePem, keys.getPrivate());
        Ed25519Keys.writePublicKey(publicPem, keys.getPublic());

        Path file = tempDir.resolve("ten.bin");
        Files.write(file, new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        ReleaseManifest signed = ManifestSigner.sign(
                ReleaseManifestFactory.unsigned(
                        "game-x", "1.4.0", file, 4, new FileChunker(4).chunk(file),
                        "2026-08-12T00:00:00Z", "2026-09-12T00:00:00Z", 1, "k"),
                Ed25519Keys.readPrivateKey(privatePem));

        assertThatCode(() -> ManifestVerifier.verify(signed, Ed25519Keys.readPublicKey(publicPem)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPemWithWrongHeader() {
        String pem = Ed25519Keys.toPem("PUBLIC KEY", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> Ed25519Keys.fromPem(pem, "PRIVATE KEY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BEGIN PRIVATE KEY");
    }
}
