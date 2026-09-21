package com.prabin.swarmedge.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P9-04 acceptance: the runner records samples and still verifies. It does not
 * assert that hashing or signing is fast.
 */
class SecurityOverheadRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void aRunRecordsPositiveSamplesAndStillVerifies() throws Exception {
        SecurityOverheadConfig config = new SecurityOverheadConfig("b9-test", "B9", 5, 20260921L, 4096);
        SecurityOverheadRunner.Summary summary = new SecurityOverheadRunner(config, tempDir).run();

        assertThat(summary.scenarioId()).isEqualTo("b9-test");
        assertThat(summary.baseline()).isEqualTo("B9");
        assertThat(summary.payloadBytes()).isEqualTo(4096);
        assertThat(summary.sha256Hex()).hasSize(64);
        assertThat(summary.hash().nanos()).hasSize(5);
        assertThat(summary.sign().nanos()).hasSize(5);
        assertThat(summary.verify().nanos()).hasSize(5);
        assertThat(summary.hmac().nanos()).hasSize(5);
        assertThat(summary.hash().min()).isPositive();
        assertThat(summary.sign().min()).isPositive();
        assertThat(summary.verify().min()).isPositive();
        assertThat(summary.hmac().min()).isPositive();
    }
}
