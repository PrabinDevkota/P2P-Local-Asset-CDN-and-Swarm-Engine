package com.prabin.swarmedge.benchmark;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class B0ScenarioConfigTest {

    private static final Path COMMITTED_CONFIG =
            Path.of("..", "research", "configs", "b0-origin-only.yaml");

    @Test
    void theCommittedB0ConfigParses() throws Exception {
        assertThat(Files.isRegularFile(COMMITTED_CONFIG))
                .as("research/configs/b0-origin-only.yaml must stay in the repository")
                .isTrue();

        B0ScenarioConfig config = B0ScenarioConfig.load(COMMITTED_CONFIG);

        assertThat(config.scenarioId()).isEqualTo("b0-origin-only");
        assertThat(config.baseline()).isEqualTo("B0");
        assertThat(config.repetitions()).isEqualTo(3);
        assertThat(config.coldCache()).isTrue();
        assertThat(config.chunkSizeBytes()).isEqualTo(4 * 1024 * 1024);
        assertThat(config.initialBackoff()).isEqualTo(Duration.ofMillis(200));
        assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aMissingSectionIsRejected() {
        assertThatThrownBy(() -> B0ScenarioConfig.parse(new StringReader("""
                scenarioId: x
                baseline: B0
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing section: asset");
    }

    @Test
    void aMissingKeyNamesTheKey() {
        assertThatThrownBy(() -> B0ScenarioConfig.parse(new StringReader(minimal().replace(
                "  seed: 1\n", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seed");
    }

    @Test
    void zeroRepetitionsIsRejectedBecauseOneRunIsNotAnExperiment() {
        assertThatThrownBy(() -> B0ScenarioConfig.parse(new StringReader(minimal().replace(
                "  repetitions: 3", "  repetitions: 0"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run.repetitions");
    }

    @Test
    void aFileNameWithAPathIsRejected() {
        assertThatThrownBy(() -> B0ScenarioConfig.parse(new StringReader(minimal().replace(
                "  fileName: game-x.bin", "  fileName: ../secret.bin"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain name");
    }

    @Test
    void anEmptyFileIsRejected() {
        assertThatThrownBy(() -> B0ScenarioConfig.parse(new StringReader("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void duplicateKeysAreRejectedSoARunRecordIsUnambiguous() {
        assertThatThrownBy(() -> B0ScenarioConfig.parse(new StringReader(
                minimal() + "\nscenarioId: someone-else\n")))
                .isInstanceOf(RuntimeException.class);
    }

    private static String minimal() {
        return """
                scenarioId: b0-test
                baseline: B0
                asset:
                  productId: game-x
                  version: 1.4.0
                  fileName: game-x.bin
                  sizeBytes: 320
                  chunkSizeBytes: 64
                run:
                  repetitions: 3
                  seed: 1
                  coldCache: true
                origin:
                  maxAttempts: 3
                  initialBackoffMillis: 10
                  maxBackoffMillis: 40
                  connectTimeoutMillis: 2000
                  requestTimeoutMillis: 5000
                """;
    }
}
