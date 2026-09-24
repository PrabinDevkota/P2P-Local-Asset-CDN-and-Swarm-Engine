package com.prabin.swarmedge.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaperScenarioTest {

    @Test
    void everyCommittedScenarioNamesThePaperFactors() throws Exception {
        Path configs = Path.of("research", "configs");
        if (!Files.isDirectory(configs)) {
            configs = Path.of("..", "research", "configs");
        }
        try (Stream<Path> files = Files.list(configs)) {
            List<Path> yaml = files.filter(path -> path.getFileName().toString().endsWith(".yaml")).toList();
            assertThat(yaml).isNotEmpty();
            for (Path file : yaml) {
                PaperScenario scenario = PaperScenario.load(file);
                assertThat(scenario.baseline()).isNotBlank();
                assertThat(scenario.clients()).isPositive();
                assertThat(scenario.cache()).isIn("cold", "warm");
                assertThat(scenario.repetitions()).isPositive();
            }
        }
    }

    @Test
    void aScenarioWithoutScaleIsRejected() {
        String yaml = """
                scenarioId: missing
                baseline: B0
                cacheState: cold
                run:
                  repetitions: 1
                  seed: 1
                network:
                  profile: loopback
                  delayMillis: 0
                  jitterMillis: 0
                  lossPercent: 0
                churn:
                  mode: none
                """;
        assertThatThrownBy(() -> PaperScenario.parse(new java.io.StringReader(yaml)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scale");
    }
}
