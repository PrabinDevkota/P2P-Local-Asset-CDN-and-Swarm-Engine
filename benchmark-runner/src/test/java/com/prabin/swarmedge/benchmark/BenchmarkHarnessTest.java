package com.prabin.swarmedge.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkHarnessTest {

    @TempDir
    Path tempDir;

    @Test
    void aFailedRunStillRemovesTheImpairmentAndKeepsTheRecord() throws Exception {
        Path config = writeConfig(tempDir.resolve("scenario.yaml"));
        FlagSession impairment = new FlagSession();
        BenchmarkHarness harness = new BenchmarkHarness(tempDir.resolve("raw"), impairment);

        assertThatThrownBy(() -> harness.execute(config, "run-1", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(impairment.applied).isFalse();
        assertThat(impairment.removes).isEqualTo(1);
        Path folder = tempDir.resolve("raw").resolve("run-1");
        assertThat(Files.readString(folder.resolve("validation.json"))).contains("\"passed\":false");
        assertThat(Files.readString(folder.resolve("git_commit.txt"))).isNotBlank();
        assertThat(Files.readString(folder.resolve("seed.txt"))).contains("7");
        assertThat(gunzip(folder.resolve("events.csv.gz"))).contains("run,failed");
        assertThat(folder.resolve("config.yaml")).exists();
        assertThat(folder.resolve("environment.json")).exists();
        assertThat(folder.resolve("summary.json")).exists();
        assertThat(folder.resolve("peer_metrics.csv.gz")).exists();
        assertThat(folder.resolve("stdout").resolve("runner.log")).exists();
    }

    @Test
    void aSuccessfulRunIsNotOverwritten() throws Exception {
        Path config = writeConfig(tempDir.resolve("scenario.yaml"));
        BenchmarkHarness harness = new BenchmarkHarness(tempDir.resolve("raw"), ImpairmentSession.none());
        harness.execute(config, "run-ok", () -> "{\"assetSha256\":\"abc\"}");
        assertThatThrownBy(() -> harness.execute(config, "run-ok", () -> "{\"assetSha256\":\"def\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already recorded");
        assertThat(Files.readString(tempDir.resolve("raw").resolve("run-ok").resolve("summary.json")))
                .contains("abc");
    }

    @Test
    void tenTwentyFiveAndFiftyClientsAllFinish() throws Exception {
        for (int clients : new int[] {10, 25, 50}) {
            AtomicInteger hits = new AtomicInteger();
            var results = ClientFanout.run(clients, () -> hits.incrementAndGet());
            assertThat(results).hasSize(clients);
        }
        assertThat(ClientFanout.run(10, () -> "ok")).hasSize(10);
    }

    private static Path writeConfig(Path file) throws Exception {
        Files.writeString(file, """
                scenarioId: harness
                baseline: B0
                cacheState: cold
                scale:
                  peers: 0
                  clients: 1
                network:
                  profile: loopback
                  delayMillis: 0
                  jitterMillis: 0
                  lossPercent: 0
                churn:
                  mode: none
                run:
                  repetitions: 1
                  seed: 7
                """);
        return file;
    }

    private static String gunzip(Path file) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class FlagSession implements ImpairmentSession {
        private boolean applied;
        private int removes;

        @Override
        public void apply() {
            applied = true;
        }

        @Override
        public void remove() {
            applied = false;
            removes++;
        }

        @Override
        public boolean applied() {
            return applied;
        }
    }
}
