package com.prabin.swarmedge.benchmark;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class B1ScenarioConfigTest {

    private static final Path COMMITTED_CONFIG =
            Path.of("..", "research", "configs", "b1-basic-swarm.yaml");

    @Test
    void theCommittedB1ConfigParses() throws Exception {
        assertThat(Files.isRegularFile(COMMITTED_CONFIG))
                .as("research/configs/b1-basic-swarm.yaml must stay in the repository")
                .isTrue();

        B1ScenarioConfig config = B1ScenarioConfig.load(COMMITTED_CONFIG);

        assertThat(config.scenarioId()).isEqualTo("b1-basic-swarm");
        assertThat(config.baseline()).isEqualTo("B1");
        assertThat(config.repetitions()).isEqualTo(3);
        assertThat(config.maxPeers()).isEqualTo(8);
        assertThat(config.outstandingRequestsPerPeer()).isEqualTo(8);
        assertThat(config.blockSizeBytes()).isEqualTo(256 * 1024);
        assertThat(config.blockTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.killFractions()).containsExactly(0.0, 0.10, 0.25);
    }

    @Test
    void theKillSharesTurnIntoWholePeersAndNeverAllOfThem() {
        B1ScenarioConfig config = B1ScenarioConfig.parse(new StringReader(minimal()));

        assertThat(config.peersToKill(0.0)).isZero();
        assertThat(config.peersToKill(0.10)).isZero();
        assertThat(config.peersToKill(0.25)).isEqualTo(1);
        assertThatThrownBy(() -> config.peersToKill(0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a configured kill share");
    }

    @Test
    void aChurnShareThatWouldKillEveryPeerIsCappedNotObeyed() {
        B1ScenarioConfig config = B1ScenarioConfig.parse(new StringReader(minimal().replace(
                "  killFractions: [0.0, 0.10, 0.25]", "  killFractions: [0.99]")));

        // Killing everything is not a churn smoke test, it is a failure case.
        assertThat(config.peersToKill(0.99)).isEqualTo(config.seederCount() - 1);
    }

    @Test
    void aKillShareOfOneOrMoreIsRejected() {
        assertThatThrownBy(() -> B1ScenarioConfig.parse(new StringReader(minimal().replace(
                "  killFractions: [0.0, 0.10, 0.25]", "  killFractions: [1.0]"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below 1");
        assertThatThrownBy(() -> B1ScenarioConfig.parse(new StringReader(minimal().replace(
                "  killFractions: [0.0, 0.10, 0.25]", "  killFractions: [-0.1]"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> B1ScenarioConfig.parse(new StringReader(minimal().replace(
                "  killFractions: [0.0, 0.10, 0.25]", "  killFractions: []"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one share");
    }

    @Test
    void aBlockLargerThanAChunkIsRejected() {
        assertThatThrownBy(() -> B1ScenarioConfig.parse(new StringReader(minimal().replace(
                "  blockSizeBytes: 16", "  blockSizeBytes: 128"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed");
    }

    @Test
    void aMissingSectionIsRejected() {
        assertThatThrownBy(() -> B1ScenarioConfig.parse(new StringReader("""
                scenarioId: x
                baseline: B1
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing section: asset");
    }

    @Test
    void aMissingKeyNamesTheKey() {
        assertThatThrownBy(() -> B1ScenarioConfig.parse(new StringReader(minimal().replace(
                "  maxPeers: 4\n", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPeers");
    }

    @Test
    void aZeroTimeoutIsRejectedBecauseItWouldFireImmediately() {
        assertThatThrownBy(() -> B1ScenarioConfig.parse(new StringReader(minimal().replace(
                "  blockTimeoutMillis: 5000", "  blockTimeoutMillis: 0"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockTimeoutMillis");
    }

    @Test
    void aFileNameWithAPathIsRejected() {
        assertThatThrownBy(() -> B1ScenarioConfig.parse(new StringReader(minimal().replace(
                "  fileName: game-x.bin", "  fileName: ../secret.bin"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain name");
    }

    @Test
    void anEmptyFileIsRejected() {
        assertThatThrownBy(() -> B1ScenarioConfig.parse(new StringReader("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void duplicateKeysAreRejectedSoARunRecordIsUnambiguous() {
        assertThatThrownBy(() -> B1ScenarioConfig.parse(new StringReader(
                minimal() + "\nscenarioId: someone-else\n")))
                .isInstanceOf(RuntimeException.class);
    }

    private static String minimal() {
        return """
                scenarioId: b1-test
                baseline: B1
                asset:
                  productId: game-x
                  version: 1.4.0
                  fileName: game-x.bin
                  sizeBytes: 320
                  chunkSizeBytes: 64
                run:
                  repetitions: 2
                  seed: 1
                  coldCache: true
                swarm:
                  maxPeers: 4
                  seederCount: 4
                  blockSizeBytes: 16
                  outstandingRequestsPerPeer: 8
                  blockTimeoutMillis: 5000
                  handshakeTimeoutMillis: 5000
                  connectTimeoutMillis: 2000
                  maxAttemptsPerBlock: 3
                churn:
                  killFractions: [0.0, 0.10, 0.25]
                  everyPeerHoldsEverything: true
                """;
    }
}
