package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.common.id.Hex;
import com.prabin.swarmedge.manifest.AssetMaterializer;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.peer.origin.OriginDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Baseline B0: repeat an origin-only download and record what each run cost.
 *
 * <p>The runner measures; it does not judge. It asserts nothing about throughput
 * or offload, and the only correctness claim it makes is the one the blueprint
 * demands: every repetition must rebuild a byte-identical asset.
 */
public final class B0Runner {

    private static final Logger log = LoggerFactory.getLogger(B0Runner.class);

    private final B0ScenarioConfig config;
    private final URI originBaseUri;
    private final Path workDir;

    public B0Runner(B0ScenarioConfig config, URI originBaseUri, Path workDir) {
        this.config = Objects.requireNonNull(config, "config");
        this.originBaseUri = Objects.requireNonNull(originBaseUri, "originBaseUri");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
    }

    /** The manifest must already be signature-verified by the caller. */
    public Summary run(ReleaseManifest manifest) throws IOException, InterruptedException {
        Objects.requireNonNull(manifest, "manifest");
        Files.createDirectories(workDir);
        Path sharedStore = workDir.resolve("shared-store");
        List<RunResult> runs = new ArrayList<>(config.repetitions());

        for (int repetition = 1; repetition <= config.repetitions(); repetition++) {
            String runId = config.scenarioId() + "-r" + repetition;
            Path storeRoot = config.coldCache() ? workDir.resolve(runId).resolve("store") : sharedStore;
            Path output = workDir.resolve(runId).resolve(manifest.fileName());

            ChunkStore store = new ChunkStore(storeRoot);
            OriginDownloader downloader = new OriginDownloader(originBaseUri, store, settings(runId), Thread::sleep);

            long startedAt = System.nanoTime();
            OriginDownloader.Result result = downloader.download(manifest);
            new AssetMaterializer(store).materialize(manifest.chunks(), output);
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            RunResult run = new RunResult(
                    repetition,
                    runId,
                    sha256OfFile(output),
                    Files.size(output),
                    result.bytesReceived(),
                    result.chunksDownloaded(),
                    result.chunksAlreadyCached(),
                    result.retries(),
                    elapsedMillis);
            log.info("B0 run {} asset={} originBytes={} elapsedMs={}",
                    runId, run.assetSha256(), run.originBytes(), run.elapsedMillis());
            runs.add(run);
        }
        return new Summary(config.scenarioId(), config.baseline(), config.seed(), List.copyOf(runs));
    }

    private OriginDownloader.Settings settings(String runId) {
        return new OriginDownloader.Settings(
                config.maxAttempts(),
                config.initialBackoff(),
                config.maxBackoff(),
                config.connectTimeout(),
                config.requestTimeout(),
                runId);
    }

    private static String sha256OfFile(Path file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return Hex.toLowerHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record RunResult(
            int repetition,
            String runId,
            String assetSha256,
            long assetBytes,
            long originBytes,
            int chunksDownloaded,
            int chunksReused,
            int retries,
            long elapsedMillis
    ) {
    }

    public record Summary(String scenarioId, String baseline, long seed, List<RunResult> runs) {

        /** The P2-03 acceptance test: repeats must agree byte for byte. */
        public boolean assetHashesMatch() {
            return runs.stream().map(RunResult::assetSha256).distinct().count() == 1;
        }

        public String assetSha256() {
            if (!assetHashesMatch()) {
                throw new IllegalStateException("repetitions disagree on the asset hash: " + runs);
            }
            return runs.getFirst().assetSha256();
        }

        public long totalOriginBytes() {
            return runs.stream().mapToLong(RunResult::originBytes).sum();
        }
    }
}
