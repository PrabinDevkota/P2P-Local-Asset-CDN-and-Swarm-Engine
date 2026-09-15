package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.Hex;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.Ed25519Keys;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ManifestSigner;
import com.prabin.swarmedge.manifest.ManifestVerifier;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-04 and P5-05 acceptance: every repetition rebuilds a byte-identical asset from a
 * swarm, including the repetitions where a share of the peers is killed mid-transfer.
 *
 * <p>No throughput or offload number is asserted. Those are outcomes of a measured run,
 * not properties of the code.
 */
class B1RunnerTest {

    private static final int CHUNK_SIZE = 256;
    private static final int BLOCK_SIZE = 64;
    private static final int TOTAL_CHUNKS = 6;
    private static final String ASSET_NAME = "game-x.bin";

    @TempDir
    Path tempDir;

    private Path published;
    private byte[] original;
    private ReleaseManifest manifest;
    private AssetId assetId;

    @BeforeEach
    void publishAsset() throws Exception {
        original = deterministicBytes(TOTAL_CHUNKS * CHUNK_SIZE);
        published = Files.write(tempDir.resolve(ASSET_NAME), original);

        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        KeyPair keys = Ed25519Keys.generate();
        manifest = ManifestSigner.sign(
                ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                        "2026-09-14T00:00:00Z", "2026-10-14T00:00:00Z", 1, "release-key-2026-01"),
                keys.getPrivate());
        ManifestVerifier.verify(manifest, keys.getPublic());
        assetId = AssetId.of(filled((byte) 0x11, 32));
    }

    @Test
    void everyRepeatRebuildsAByteIdenticalAssetFromTheSwarm() throws Exception {
        B1Runner.Summary summary = runner(config(2, List.of(0.0))).run(manifest);

        assertThat(summary.runs()).hasSize(2);
        assertThat(summary.assetHashesMatch()).isTrue();
        assertThat(summary.assetSha256()).isEqualTo(sha256Hex(original));
        assertThat(summary.scenarioId()).isEqualTo("b1-test");
        assertThat(summary.baseline()).isEqualTo("B1");
        assertThat(summary.runs()).extracting(B1Runner.RunResult::runId)
                .containsExactly("b1-test-k0-r1", "b1-test-k0-r2");
    }

    @Test
    void theAssetIsRebuiltFromPeerBytesOnly() throws Exception {
        B1Runner.Summary summary = runner(config(1, List.of(0.0))).run(manifest);

        assertThat(summary.runs()).allSatisfy(run -> {
            assertThat(run.assetBytes()).isEqualTo(original.length);
            assertThat(run.peerBytes()).isPositive();
            assertThat(run.peersDialled()).isPositive();
            assertThat(run.peersLost()).isZero();
        });
    }

    @Test
    void noSinglePeerCanFinishTheJobSoAllOfThemAreUsed() throws Exception {
        // Each seeder holds only the chunks where chunkIndex % seederCount matches it.
        B1Runner.Summary summary = runner(config(1, List.of(0.0), false)).run(manifest);

        assertThat(summary.assetSha256()).isEqualTo(sha256Hex(original));
        assertThat(summary.runs().getFirst().peersDialled()).isEqualTo(4);
    }

    @Test
    void killingAShareOfThePeersMidTransferStillRebuildsTheAsset() throws Exception {
        B1Runner.Summary summary = runner(config(2, List.of(0.0, 0.25))).run(manifest);

        assertThat(summary.runs()).hasSize(4);
        assertThat(summary.assetHashesMatch()).isTrue();
        assertThat(summary.assetSha256()).isEqualTo(sha256Hex(original));
        assertThat(summary.runs()).extracting(B1Runner.RunResult::runId)
                .containsExactly("b1-test-k0-r1", "b1-test-k0-r2", "b1-test-k25-r1", "b1-test-k25-r2");
    }

    @Test
    void aChurnRunKillsTheSamePeersEveryTimeSoItCanBeReplayed() throws Exception {
        B1Runner.Summary first = runner(config(1, List.of(0.25)), "work-1").run(manifest);
        B1Runner.Summary second = runner(config(1, List.of(0.25)), "work-2").run(manifest);

        assertThat(first.assetSha256()).isEqualTo(second.assetSha256());
        assertThat(first.runs().getFirst().killFraction())
                .isEqualTo(second.runs().getFirst().killFraction());
    }

    private B1Runner runner(B1ScenarioConfig config) {
        return runner(config, "work");
    }

    private B1Runner runner(B1ScenarioConfig config, String workName) {
        return new B1Runner(config, tempDir.resolve(workName), published, assetId);
    }

    private static B1ScenarioConfig config(int repetitions, List<Double> killFractions) {
        return config(repetitions, killFractions, true);
    }

    private static B1ScenarioConfig config(int repetitions, List<Double> killFractions,
                                           boolean everyPeerHoldsEverything) {
        String shares = killFractions.toString();
        return B1ScenarioConfig.parse(new StringReader("""
                scenarioId: b1-test
                baseline: B1
                asset:
                  productId: game-x
                  version: 1.4.0
                  fileName: %s
                  sizeBytes: %d
                  chunkSizeBytes: %d
                run:
                  repetitions: %d
                  seed: 20260914
                  coldCache: true
                swarm:
                  maxPeers: 4
                  seederCount: 4
                  blockSizeBytes: %d
                  outstandingRequestsPerPeer: 8
                  blockTimeoutMillis: 5000
                  handshakeTimeoutMillis: 5000
                  connectTimeoutMillis: 2000
                  stallTimeoutMillis: 20000
                  maxAttemptsPerBlock: 3
                churn:
                  killFractions: %s
                  everyPeerHoldsEverything: %s
                """.formatted(ASSET_NAME, TOTAL_CHUNKS * CHUNK_SIZE, CHUNK_SIZE, repetitions,
                BLOCK_SIZE, shares, everyPeerHoldsEverything)));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return Hex.toLowerHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] filled(byte value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, value);
        return out;
    }

    private static byte[] deterministicBytes(int size) {
        byte[] data = new byte[size];
        new Random(20260914L).nextBytes(data);
        return data;
    }
}
