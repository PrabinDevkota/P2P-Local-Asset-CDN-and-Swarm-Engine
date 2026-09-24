package com.prabin.swarmedge.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReproducePaperTest {

    @TempDir
    Path tempDir;

    @Test
    void aSecondRunStillRewritesTheTable() throws Exception {
        Path raw = tempDir.resolve("raw");
        Path processed = tempDir.resolve("processed");
        Path first = ReproducePaper.run(tempDir.resolve("work-1"), raw, processed);
        Path second = ReproducePaper.run(tempDir.resolve("work-2"), raw, processed);

        assertThat(Files.readString(first)).contains("Hashes agree: true");
        assertThat(second).isEqualTo(first);
        assertThat(Files.list(raw).count()).isEqualTo(2);
    }
}
