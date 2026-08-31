package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestCliTest {

    @TempDir
    Path tempDir;

    @Test
    void genKeySignAndVerifyRoundTrip() throws Exception {
        Path keys = tempDir.resolve("keys");
        assertThat(run("gen-key", "--out-dir", keys.toString())).isZero();
        assertThat(keys.resolve("private.pem")).exists();
        assertThat(keys.resolve("public.pem")).exists();

        Path file = tempDir.resolve("ten.bin");
        Files.write(file, new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        Path store = tempDir.resolve("store");
        Path manifestPath = tempDir.resolve("release.json");

        Capture sign = capture("sign",
                "--file", file.toString(),
                "--product", "game-x",
                "--version", "1.4.0",
                "--signing-key-id", "release-key-2026-01",
                "--private-key", keys.resolve("private.pem").toString(),
                "--store", store.toString(),
                "--out", manifestPath.toString(),
                "--chunk-size", "4");
        assertThat(sign.code()).isZero();

        ReleaseManifest parsed = ManifestJson.parse(Files.readString(manifestPath));
        assertThat(parsed.chunks()).hasSize(3);
        assertThat(parsed.signature()).isNotBlank();
        ChunkStore chunkStore = new ChunkStore(store);
        assertThat(chunkStore.contains(parsed.chunks().get(0).sha256())).isTrue();
        assertThat(chunkStore.contains(parsed.chunks().get(2).sha256())).isTrue();
        assertThat(sign.out()).contains(CanonicalManifest.assetId(parsed).toHex());

        Capture verify = capture("verify",
                "--manifest", manifestPath.toString(),
                "--public-key", keys.resolve("public.pem").toString());
        assertThat(verify.code()).isZero();
        assertThat(verify.out()).contains(CanonicalManifest.assetId(parsed).toHex());
    }

    @Test
    void verifyWithWrongKeyFails() throws Exception {
        Path publisher = tempDir.resolve("publisher");
        Path impostor = tempDir.resolve("impostor");
        assertThat(run("gen-key", "--out-dir", publisher.toString())).isZero();
        assertThat(run("gen-key", "--out-dir", impostor.toString())).isZero();

        Path file = tempDir.resolve("ten.bin");
        Files.write(file, new byte[] {1, 2, 3, 4});
        Path manifestPath = tempDir.resolve("release.json");
        assertThat(run("sign",
                "--file", file.toString(),
                "--product", "p",
                "--version", "1",
                "--signing-key-id", "k",
                "--private-key", publisher.resolve("private.pem").toString(),
                "--store", tempDir.resolve("store").toString(),
                "--out", manifestPath.toString(),
                "--chunk-size", "4")).isZero();

        Capture verify = capture("verify",
                "--manifest", manifestPath.toString(),
                "--public-key", impostor.resolve("public.pem").toString());
        assertThat(verify.code()).isEqualTo(1);
        assertThat(verify.err()).contains("invalid Ed25519 signature");
    }

    @Test
    void unknownCommandPrintsUsage() {
        Capture capture = capture("not-a-command");
        assertThat(capture.code()).isEqualTo(2);
        assertThat(capture.err()).contains("Usage:");
    }

    private int run(String... args) {
        return capture(args).code();
    }

    private Capture capture(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = ManifestCli.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
