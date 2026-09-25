package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.manifest.VersionMutator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CrossVersionStudyTest {

    @TempDir
    Path tempDir;

    @Test
    void b4AndB5BothReportAnIntervalAndDoNotClaimAWinner() throws Exception {
        byte[] base = new byte[2_000];
        for (int i = 0; i < base.length; i++) {
            base[i] = (byte) (i * 19 + 1);
        }
        var reports = CrossVersionStudy.run(tempDir, base,
                VersionMutator.edit("insert", 400, 8),
                new byte[] {9, 8, 7, 6, 5, 4, 3, 2},
                128, 32, 64, 256, 5);

        assertThat(reports).extracting(CrossVersionStudy.Report::baseline).containsExactly("B4", "B5");
        for (CrossVersionStudy.Report report : reports) {
            assertThat(report.samples()).hasSize(5);
            assertOrdered(report.sharedBytes());
            assertOrdered(report.manifestBytes());
            assertOrdered(report.chunkNanos());
            assertOrdered(report.elapsedNanos());
            assertThat(report.sharedBytes().median()).isBetween(0L, (long) base.length);
            assertThat(report.manifestBytes().median()).isPositive();
        }
        String table = CrossVersionStudy.table(reports);
        assertThat(table).contains("B4", "B5");
        assertThat(table).doesNotContain("wins");
        assertThat(table).doesNotContain("ratio");
    }

    @Test
    void identicalBytesShareTheWholeFile() throws Exception {
        byte[] base = new byte[300];
        Arrays.fill(base, (byte) 4);
        Path file = tempDir.resolve("same.bin");
        java.nio.file.Files.write(file, base);
        var chunker = new com.prabin.swarmedge.manifest.FileChunker(64);
        long shared = CrossVersionStudy.sharedBytes(chunker.chunk(file), chunker.chunk(file));
        assertThat(shared).isEqualTo(base.length);
    }

    private static void assertOrdered(CrossVersionStudy.Interval interval) {
        assertThat(interval.low()).isLessThanOrEqualTo(interval.median());
        assertThat(interval.high()).isGreaterThanOrEqualTo(interval.median());
    }
}
