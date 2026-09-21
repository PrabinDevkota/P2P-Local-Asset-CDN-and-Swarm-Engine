package com.prabin.swarmedge.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityOverheadConfigTest {

    private static final Path COMMITTED_CONFIG =
            Path.of("..", "research", "configs", "b9-security-overhead.yaml");

    @Test
    void theCommittedB9ConfigParses() throws Exception {
        assertThat(Files.isRegularFile(COMMITTED_CONFIG))
                .as("research/configs/b9-security-overhead.yaml must stay in the repository")
                .isTrue();

        SecurityOverheadConfig config = SecurityOverheadConfig.load(COMMITTED_CONFIG);

        assertThat(config.scenarioId()).isEqualTo("b9-security-overhead");
        assertThat(config.baseline()).isEqualTo("B9");
        assertThat(config.repetitions()).isEqualTo(25);
        assertThat(config.seed()).isEqualTo(20260921L);
        assertThat(config.payloadBytes()).isEqualTo(262144);
    }

    @Test
    void aMissingSectionIsRejected() {
        assertThatThrownBy(() -> SecurityOverheadConfig.parse(new java.io.StringReader("""
                scenarioId: x
                baseline: B9
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing section: run");
    }
}
