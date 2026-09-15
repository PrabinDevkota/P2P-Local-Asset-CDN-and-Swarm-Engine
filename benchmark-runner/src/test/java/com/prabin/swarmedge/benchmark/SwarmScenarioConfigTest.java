package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.peer.laps.LapsWeights;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SwarmScenarioConfigTest {

    private static final Path CONFIG_DIR = Path.of("..", "research", "configs");
    private static final Path COMMITTED_CONFIG = CONFIG_DIR.resolve("b1-basic-swarm.yaml");

    @Test
    void theCommittedB1ConfigParses() throws Exception {
        assertThat(Files.isRegularFile(COMMITTED_CONFIG))
                .as("research/configs/b1-basic-swarm.yaml must stay in the repository")
                .isTrue();

        SwarmScenarioConfig config = SwarmScenarioConfig.load(COMMITTED_CONFIG);

        assertThat(config.scenarioId()).isEqualTo("b1-basic-swarm");
        assertThat(config.baseline()).isEqualTo("B1");
        assertThat(config.repetitions()).isEqualTo(3);
        assertThat(config.maxPeers()).isEqualTo(8);
        assertThat(config.outstandingRequestsPerPeer()).isEqualTo(8);
        assertThat(config.blockSizeBytes()).isEqualTo(256 * 1024);
        assertThat(config.blockTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.stallTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(config.killFractions()).containsExactly(0.0, 0.10, 0.25);
        assertThat(config.sourcePolicy()).isEqualTo(SwarmScenarioConfig.SourcePolicy.AS_DISCOVERED);
        assertThat(config.lapsWeights()).isNull();
        assertThat(config.endgameThresholdBlocks()).isZero();
    }

    @Test
    void theThreeBaselinesDifferOnlyInTheirSchedulerSection() throws Exception {
        // P6-05's acceptance test, and the only reason comparing the baselines means
        // anything. If the asset, the seed, or the pipeline moved as well, a difference
        // in the results would have more than one possible cause.
        SwarmScenarioConfig b1 = SwarmScenarioConfig.load(COMMITTED_CONFIG);
        SwarmScenarioConfig b2 = SwarmScenarioConfig.load(CONFIG_DIR.resolve("b2-locality.yaml"));
        SwarmScenarioConfig b3 = SwarmScenarioConfig.load(CONFIG_DIR.resolve("b3-laps.yaml"));

        for (SwarmScenarioConfig other : List.of(b2, b3)) {
            assertThat(other.seed()).isEqualTo(b1.seed());
            assertThat(other.repetitions()).isEqualTo(b1.repetitions());
            assertThat(other.coldCache()).isEqualTo(b1.coldCache());
            assertThat(other.sizeBytes()).isEqualTo(b1.sizeBytes());
            assertThat(other.chunkSizeBytes()).isEqualTo(b1.chunkSizeBytes());
            assertThat(other.fileName()).isEqualTo(b1.fileName());
            assertThat(other.productId()).isEqualTo(b1.productId());
            assertThat(other.version()).isEqualTo(b1.version());
            assertThat(other.maxPeers()).isEqualTo(b1.maxPeers());
            assertThat(other.seederCount()).isEqualTo(b1.seederCount());
            assertThat(other.blockSizeBytes()).isEqualTo(b1.blockSizeBytes());
            assertThat(other.outstandingRequestsPerPeer()).isEqualTo(b1.outstandingRequestsPerPeer());
            assertThat(other.blockTimeout()).isEqualTo(b1.blockTimeout());
            assertThat(other.handshakeTimeout()).isEqualTo(b1.handshakeTimeout());
            assertThat(other.connectTimeout()).isEqualTo(b1.connectTimeout());
            assertThat(other.stallTimeout()).isEqualTo(b1.stallTimeout());
            assertThat(other.maxAttemptsPerBlock()).isEqualTo(b1.maxAttemptsPerBlock());
            assertThat(other.killFractions()).isEqualTo(b1.killFractions());
            assertThat(other.everyPeerHoldsEverything()).isEqualTo(b1.everyPeerHoldsEverything());
        }

        // ...and the scheduler section is where they are allowed to disagree.
        assertThat(List.of(b1.sourcePolicy(), b2.sourcePolicy(), b3.sourcePolicy()))
                .containsExactly(SwarmScenarioConfig.SourcePolicy.AS_DISCOVERED,
                        SwarmScenarioConfig.SourcePolicy.LOCALITY_ONLY,
                        SwarmScenarioConfig.SourcePolicy.LAPS);
        assertThat(b2.lapsWeights()).isEqualTo(LapsWeights.localityOnly());
        assertThat(b3.lapsWeights()).isEqualTo(LapsWeights.defaults());
        assertThat(b3.endgameThresholdBlocks()).isEqualTo(16);
    }

    @Test
    void weightsAreWrittenOutRatherThanImpliedByThePolicyName() {
        // A run record that only said "LAPS" would not say which weights produced it,
        // and varying them is the whole point of a sensitivity sweep.
        SwarmScenarioConfig config = SwarmScenarioConfig.parse(new StringReader(
                withPolicy("LAPS", 0.5, 0.2, 0.1, 0.1, 0.1)));

        assertThat(config.lapsWeights()).isEqualTo(new LapsWeights(0.5, 0.2, 0.1, 0.1, 0.1));
        assertThat(config.lapsWeights()).isNotEqualTo(LapsWeights.defaults());
    }

    @Test
    void aBaselineThatDoesNotScorePeersMustNotCarryWeights() {
        // Otherwise a run record implies a policy the run did not use.
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(
                withPolicy("AS_DISCOVERED", 1.0, 0.0, 0.0, 0.0, 0.0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no meaning under AS_DISCOVERED");
    }

    @Test
    void anUnknownSourcePolicyNamesTheOnesThatExist() {
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(minimal()
                .replace("sourcePolicy: AS_DISCOVERED", "sourcePolicy: MAGIC"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AS_DISCOVERED")
                .hasMessageContaining("LAPS");
    }

    @Test
    void weightsThatDoNotSumToOneAreRejectedInAConfigToo() {
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(
                withPolicy("LAPS", 0.9, 0.9, 0.0, 0.0, 0.0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must sum to 1");
    }

    /** The minimal config with its scheduler section replaced, indentation and all. */
    private static String withPolicy(String policy, double locality, double throughput,
                                     double rtt, double capacity, double health) {
        return minimal().replace("  sourcePolicy: AS_DISCOVERED\n",
                "  sourcePolicy: " + policy + "\n"
                        + "  lapsWeights:\n"
                        + "    locality: " + locality + "\n"
                        + "    throughput: " + throughput + "\n"
                        + "    rtt: " + rtt + "\n"
                        + "    capacity: " + capacity + "\n"
                        + "    health: " + health + "\n");
    }

    @Test
    void aNegativeEndgameThresholdIsRejected() {
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(minimal()
                .replace("  endgameThresholdBlocks: 0", "  endgameThresholdBlocks: -1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endgameThresholdBlocks");
    }

    @Test
    void aStallDeadlineThatCouldFireDuringOneSlowBlockIsRejected() {
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(minimal().replace(
                "  stallTimeoutMillis: 20000", "  stallTimeoutMillis: 5000"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must outlast swarm.blockTimeoutMillis");
    }

    @Test
    void theKillSharesTurnIntoWholePeersAndNeverAllOfThem() {
        SwarmScenarioConfig config = SwarmScenarioConfig.parse(new StringReader(minimal()));

        assertThat(config.peersToKill(0.0)).isZero();
        assertThat(config.peersToKill(0.10)).isZero();
        assertThat(config.peersToKill(0.25)).isEqualTo(1);
        assertThatThrownBy(() -> config.peersToKill(0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a configured kill share");
    }

    @Test
    void aChurnShareThatWouldKillEveryPeerIsCappedNotObeyed() {
        SwarmScenarioConfig config = SwarmScenarioConfig.parse(new StringReader(minimal().replace(
                "  killFractions: [0.0, 0.10, 0.25]", "  killFractions: [0.99]")));

        // Killing everything is not a churn smoke test, it is a failure case.
        assertThat(config.peersToKill(0.99)).isEqualTo(config.seederCount() - 1);
    }

    @Test
    void aKillShareOfOneOrMoreIsRejected() {
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(minimal().replace(
                "  killFractions: [0.0, 0.10, 0.25]", "  killFractions: [1.0]"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below 1");
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(minimal().replace(
                "  killFractions: [0.0, 0.10, 0.25]", "  killFractions: [-0.1]"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(minimal().replace(
                "  killFractions: [0.0, 0.10, 0.25]", "  killFractions: []"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one share");
    }

    @Test
    void aBlockLargerThanAChunkIsRejected() {
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(minimal().replace(
                "  blockSizeBytes: 16", "  blockSizeBytes: 128"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed");
    }

    @Test
    void aMissingSectionIsRejected() {
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader("""
                scenarioId: x
                baseline: B1
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing section: asset");
    }

    @Test
    void aMissingKeyNamesTheKey() {
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(minimal().replace(
                "  maxPeers: 4\n", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPeers");
    }

    @Test
    void aZeroTimeoutIsRejectedBecauseItWouldFireImmediately() {
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(minimal().replace(
                "  blockTimeoutMillis: 5000", "  blockTimeoutMillis: 0"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockTimeoutMillis");
    }

    @Test
    void aFileNameWithAPathIsRejected() {
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(minimal().replace(
                "  fileName: game-x.bin", "  fileName: ../secret.bin"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain name");
    }

    @Test
    void anEmptyFileIsRejected() {
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void duplicateKeysAreRejectedSoARunRecordIsUnambiguous() {
        assertThatThrownBy(() -> SwarmScenarioConfig.parse(new StringReader(
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
                  stallTimeoutMillis: 20000
                  maxAttemptsPerBlock: 3
                scheduler:
                  sourcePolicy: AS_DISCOVERED
                  endgameThresholdBlocks: 0
                churn:
                  killFractions: [0.0, 0.10, 0.25]
                  everyPeerHoldsEverything: true
                """;
    }
}
