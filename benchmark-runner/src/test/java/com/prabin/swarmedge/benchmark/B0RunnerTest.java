package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.common.id.Hex;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.Ed25519Keys;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ManifestSigner;
import com.prabin.swarmedge.manifest.ManifestVerifier;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.origin.OriginHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-03 acceptance: three repeat runs rebuild the same asset and produce a
 * structured result. No throughput or offload number is asserted here — those
 * are outcomes of a measured run, not properties of the code.
 */
class B0RunnerTest {

    private static final int CHUNK_SIZE = 64;
    private static final int TOTAL_CHUNKS = 5;
    private static final String ASSET_NAME = "game-x.bin";

    @TempDir
    Path tempDir;

    private Path originRoot;
    private byte[] original;
    private ReleaseManifest manifest;

    @BeforeEach
    void publishAsset() throws Exception {
        originRoot = Files.createDirectories(tempDir.resolve("origin"));
        original = deterministicBytes(TOTAL_CHUNKS * CHUNK_SIZE);
        Path published = Files.write(originRoot.resolve(ASSET_NAME), original);

        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        KeyPair keys = Ed25519Keys.generate();
        manifest = ManifestSigner.sign(
                ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                        "2026-09-12T00:00:00Z", "2026-10-12T00:00:00Z", 1, "release-key-2026-01"),
                keys.getPrivate());
        ManifestVerifier.verify(manifest, keys.getPublic());
    }

    @Test
    void threeColdRepeatsRebuildAByteIdenticalAsset() throws Exception {
        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            B0Runner.Summary summary = new B0Runner(config(3, true), origin.baseUri(), tempDir.resolve("work"))
                    .run(manifest);

            assertThat(summary.runs()).hasSize(3);
            assertThat(summary.assetHashesMatch()).isTrue();
            assertThat(summary.assetSha256()).isEqualTo(sha256Hex(original));
            assertThat(summary.scenarioId()).isEqualTo("b0-test");
            assertThat(summary.baseline()).isEqualTo("B0");
            assertThat(summary.runs())
                    .extracting(B0Runner.RunResult::runId)
                    .containsExactly("b0-test-r1", "b0-test-r2", "b0-test-r3");
        }
    }

    @Test
    void aColdRunPullsTheWholeAssetFromOriginEveryTime() throws Exception {
        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            B0Runner.Summary summary = new B0Runner(config(3, true), origin.baseUri(), tempDir.resolve("work"))
                    .run(manifest);

            assertThat(summary.runs())
                    .allSatisfy(run -> {
                        assertThat(run.chunksDownloaded()).isEqualTo(TOTAL_CHUNKS);
                        assertThat(run.chunksReused()).isZero();
                        assertThat(run.originBytes()).isEqualTo(original.length);
                        assertThat(run.assetBytes()).isEqualTo(original.length);
                        assertThat(run.retries()).isZero();
                    });
            assertThat(summary.totalOriginBytes()).isEqualTo(3L * original.length);
        }
    }

    @Test
    void aWarmRunReusesTheCacheAfterTheFirstRepeat() throws Exception {
        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            B0Runner.Summary summary = new B0Runner(config(3, false), origin.baseUri(), tempDir.resolve("work"))
                    .run(manifest);

            assertThat(summary.assetHashesMatch()).isTrue();
            assertThat(summary.runs().get(0).originBytes()).isEqualTo(original.length);
            assertThat(summary.runs().get(1).originBytes()).isZero();
            assertThat(summary.runs().get(2).chunksReused()).isEqualTo(TOTAL_CHUNKS);
        }
    }

    private static B0ScenarioConfig config(int repetitions, boolean coldCache) {
        return B0ScenarioConfig.parse(new StringReader("""
                scenarioId: b0-test
                baseline: B0
                asset:
                  productId: game-x
                  version: 1.4.0
                  fileName: %s
                  sizeBytes: %d
                  chunkSizeBytes: %d
                run:
                  repetitions: %d
                  seed: 20260912
                  coldCache: %s
                origin:
                  maxAttempts: 3
                  initialBackoffMillis: 10
                  maxBackoffMillis: 40
                  connectTimeoutMillis: 2000
                  requestTimeoutMillis: 5000
                """.formatted(ASSET_NAME, TOTAL_CHUNKS * CHUNK_SIZE, CHUNK_SIZE, repetitions, coldCache)));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return Hex.toLowerHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] deterministicBytes(int size) {
        byte[] data = new byte[size];
        new Random(20260912L).nextBytes(data);
        return data;
    }
}
