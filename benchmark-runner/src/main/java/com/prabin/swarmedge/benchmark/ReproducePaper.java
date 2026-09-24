package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.Ed25519Keys;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ManifestSigner;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.origin.OriginHttpServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.security.KeyPair;
import java.util.List;
import java.util.Random;

/**
 * One small B0 run written as a table (blueprint P10-04).
 *
 * <p>The asset is a few hundred bytes so a fresh checkout can finish it.
 * The table lists measured bytes and elapsed time. It does not state an offload ratio.
 */
public final class ReproducePaper {

    private ReproducePaper() {
    }

    public static void main(String[] args) throws Exception {
        Path root = locateRepo();
        Path raw = root.resolve("research").resolve("raw");
        Path processed = root.resolve("research").resolve("processed");
        Files.createDirectories(processed);
        Path work = Files.createTempDirectory("reproduce-b0");
        try {
            Path table = run(work, raw, processed);
            System.out.println(table.toAbsolutePath());
        } finally {
            deleteTree(work);
        }
    }

    static Path run(Path work, Path raw, Path processed) throws Exception {
        int chunkSize = 64;
        byte[] original = new byte[chunkSize * 5];
        new Random(20260912).nextBytes(original);
        Path originRoot = Files.createDirectories(work.resolve("origin"));
        Path published = Files.write(originRoot.resolve("game-x.bin"), original);
        List<ChunkEntry> chunks = new FileChunker(chunkSize).chunk(published);
        KeyPair keys = Ed25519Keys.generate();
        ReleaseManifest manifest = ManifestSigner.sign(
                ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, chunkSize, chunks,
                        "2026-09-12T00:00:00Z", "2027-12-31T00:00:00Z", 1, "release-key-2026-01"),
                keys.getPrivate());
        Path config = work.resolve("b0-smoke.yaml");
        Files.writeString(config, """
                scenarioId: b0-smoke
                baseline: B0
                cacheState: cold
                description: Reproduce-script smoke. Not a paper claim.
                asset:
                  productId: game-x
                  version: 1.4.0
                  fileName: game-x.bin
                  sizeBytes: %d
                  chunkSizeBytes: %d
                run:
                  repetitions: 3
                  seed: 20260912
                  coldCache: true
                origin:
                  maxAttempts: 3
                  initialBackoffMillis: 1
                  maxBackoffMillis: 5
                  connectTimeoutMillis: 5000
                  requestTimeoutMillis: 60000
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
                """.formatted(original.length, chunkSize));

        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            B0ScenarioConfig scenario = B0ScenarioConfig.load(config);
            B0Runner.Summary summary = new B0Runner(scenario, origin.baseUri(), work.resolve("runs")).run(manifest);
            String json = "{\"baseline\":\"B0\",\"assetSha256\":\"" + summary.assetSha256()
                    + "\",\"repetitions\":" + summary.runs().size() + "}";
            String runId = "b0-smoke-" + System.currentTimeMillis();
            new BenchmarkHarness(raw, ImpairmentSession.none()).execute(config, runId, () -> json);
            return writeTable(processed.resolve("b0-smoke-table.md"), summary);
        }
    }

    private static Path writeTable(Path table, B0Runner.Summary summary) throws Exception {
        StringBuilder text = new StringBuilder();
        text.append("# B0 smoke\n\n");
        text.append("Asset SHA-256 `").append(summary.assetSha256()).append("`.\n\n");
        text.append("| repetition | origin bytes | cache bytes | elapsed ms |\n");
        text.append("| --- | --- | --- | --- |\n");
        for (B0Runner.RunResult run : summary.runs()) {
            text.append("| ").append(run.repetition())
                    .append(" | ").append(run.originBytes())
                    .append(" | ").append(run.cacheBytes())
                    .append(" | ").append(run.elapsedMillis())
                    .append(" |\n");
        }
        text.append("\nHashes agree: ").append(summary.assetHashesMatch()).append(".\n");
        Files.createDirectories(table.getParent());
        Files.writeString(table, text);
        return table;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path locateRepo() {
        Path here = Path.of("").toAbsolutePath();
        if (Files.isDirectory(here.resolve("research"))) {
            return here;
        }
        if (here.getParent() != null && Files.isDirectory(here.getParent().resolve("research"))) {
            return here.getParent();
        }
        throw new IllegalStateException("run from the repository root or benchmark-runner");
    }
}
