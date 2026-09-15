package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.Hex;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.manifest.AssetMaterializer;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.peer.chunk.ChunkAssembler;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.peer.net.SeederServer;
import com.prabin.swarmedge.peer.session.BlockSender;
import com.prabin.swarmedge.peer.session.LeecherHandler;
import com.prabin.swarmedge.peer.swarm.SwarmDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Baseline B1: rebuild one asset from a swarm and record what each run cost.
 *
 * <p>The runner measures; it does not judge. It asserts nothing about throughput or
 * origin offload, and the only correctness claim it makes is the blueprint's: every
 * repetition must rebuild a byte-identical asset (P5-04), including the repetitions
 * where peers are killed mid-transfer (P5-05).
 *
 * <p>Seeders are real processes' worth of machinery in one JVM: each gets its own
 * {@link SeederServer} on a loopback port, its own chunk store, and no knowledge of the
 * others. Which peers get killed comes from the scenario seed, so a churn run is as
 * replayable as a clean one.
 */
public final class B1Runner {

    private static final Logger log = LoggerFactory.getLogger(B1Runner.class);

    private final B1ScenarioConfig config;
    private final Path workDir;
    private final Path sourceFile;
    private final AssetId assetId;

    /**
     * @param sourceFile the published asset, used to stock the seeders; a real swarm
     *                   would have fetched these chunks from origin first
     */
    public B1Runner(B1ScenarioConfig config, Path workDir, Path sourceFile, AssetId assetId) {
        this.config = Objects.requireNonNull(config, "config");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
        this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile");
        this.assetId = Objects.requireNonNull(assetId, "assetId");
    }

    /** The manifest must already be signature-verified by the caller. */
    public Summary run(ReleaseManifest manifest) throws IOException, InterruptedException {
        Objects.requireNonNull(manifest, "manifest");
        Files.createDirectories(workDir);
        Path sharedStore = workDir.resolve("shared-store");
        List<RunResult> runs = new ArrayList<>();

        for (double killFraction : config.killFractions()) {
            for (int repetition = 1; repetition <= config.repetitions(); repetition++) {
                String runId = config.scenarioId() + "-k" + (int) Math.round(killFraction * 100)
                        + "-r" + repetition;
                Path storeRoot = config.coldCache() ? workDir.resolve(runId).resolve("store") : sharedStore;
                runs.add(runOnce(manifest, runId, storeRoot, killFraction, runs.size()));
            }
        }
        return new Summary(config.scenarioId(), config.baseline(), config.seed(), List.copyOf(runs));
    }

    private RunResult runOnce(ReleaseManifest manifest, String runId, Path storeRoot,
                              double killFraction, int runIndex) throws IOException, InterruptedException {
        List<SeederServer> seeders = new ArrayList<>();
        try {
            for (int i = 0; i < config.seederCount(); i++) {
                seeders.add(startSeeder(manifest, runId, i));
            }
            List<InetSocketAddress> candidates = new ArrayList<>();
            seeders.forEach(seeder -> candidates.add(seeder.address()));

            ChunkStore store = new ChunkStore(storeRoot);
            ChunkInventory inventory = new ChunkInventory(manifest, store);
            Path output = workDir.resolve(runId).resolve(manifest.fileName());

            long startedAt = System.nanoTime();
            SwarmDownloader.Result result;
            try (ChunkAssembler assembler = new ChunkAssembler(inventory, store,
                    workDir.resolve(runId).resolve("staging"));
                 SwarmDownloader swarm = new SwarmDownloader(swarmSettings(), inventory, assembler)) {

                var asset = swarm.start(candidates);
                killPeers(seeders, killFraction, runIndex);
                result = await(asset);
            }
            new AssetMaterializer(store).materialize(manifest.chunks(), output);
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            long peerBytes = seeders.stream()
                    .mapToLong(seeder -> seeder.lastSession().map(session -> session.bytesSent()).orElse(0L))
                    .sum();
            RunResult run = new RunResult(runId, killFraction, sha256OfFile(output), Files.size(output),
                    peerBytes, result.peersDialled(), result.peersLost(), elapsedMillis);
            log.info("B1 run {} asset={} peerBytes={} peersLost={} elapsedMs={}",
                    runId, run.assetSha256(), run.peerBytes(), run.peersLost(), run.elapsedMillis());
            return run;
        } finally {
            seeders.forEach(SeederServer::close);
        }
    }

    /**
     * Take out a share of the seeders once the transfer is under way. Which ones is
     * drawn from the scenario seed and the run index, so the same config kills the same
     * peers in the same order every time.
     */
    private void killPeers(List<SeederServer> seeders, double killFraction, int runIndex) {
        int toKill = config.peersToKill(killFraction);
        if (toKill == 0) {
            return;
        }
        List<SeederServer> order = new ArrayList<>(seeders);
        Collections.shuffle(order, new Random(config.seed() + runIndex));
        for (int i = 0; i < toKill; i++) {
            log.info("churn: dropping seeder on {}", order.get(i).address());
            order.get(i).close();
        }
    }

    private SeederServer startSeeder(ReleaseManifest manifest, String runId, int index) throws IOException {
        Path root = workDir.resolve(runId).resolve("seeder-" + index);
        ChunkStore store = new ChunkStore(root);
        stock(manifest, store, index);
        ChunkInventory inventory = new ChunkInventory(manifest, store);
        return new SeederServer(SeederServer.Config.of(assetId, seederPeerId(index), inventory, store,
                BlockSender.Mode.FILE_REGION, config.blockSizeBytes()));
    }

    /**
     * Give a seeder its chunks. With {@code everyPeerHoldsEverything} off, seeder
     * {@code i} holds the chunks where {@code chunkIndex % seederCount == i}, which is
     * the interesting case: no single peer can finish the job.
     */
    private void stock(ReleaseManifest manifest, ChunkStore store, int index) throws IOException {
        byte[] asset = Files.readAllBytes(sourceFile);
        for (ChunkEntry chunk : manifest.chunks()) {
            if (!config.everyPeerHoldsEverything() && chunk.index() % config.seederCount() != index) {
                continue;
            }
            byte[] bytes = new byte[(int) chunk.length()];
            System.arraycopy(asset, (int) chunk.offset(), bytes, 0, bytes.length);
            store.putVerified(chunk.sha256(), bytes);
        }
    }

    private SwarmDownloader.Settings swarmSettings() {
        LeecherHandler.Settings session = new LeecherHandler.Settings(
                config.blockSizeBytes(),
                config.outstandingRequestsPerPeer(),
                config.blockTimeout(),
                config.maxAttemptsPerBlock(),
                config.handshakeTimeout());
        return new SwarmDownloader.Settings(assetId, leecherPeerId(), token(), session,
                config.connectTimeout(), config.maxPeers(), config.seed(), config.stallTimeout());
    }

    private static byte[] token() {
        return "b1-runner".getBytes(StandardCharsets.US_ASCII);
    }

    private static PeerId leecherPeerId() {
        byte[] bits = new byte[16];
        bits[0] = 0x01;
        return PeerId.of(bits);
    }

    private static PeerId seederPeerId(int index) {
        byte[] bits = new byte[16];
        bits[0] = 0x02;
        bits[1] = (byte) index;
        return PeerId.of(bits);
    }

    private <T> T await(java.util.concurrent.CompletableFuture<T> future)
            throws IOException, InterruptedException {
        try {
            // Generous: the point is to catch a hang, not to time the transfer.
            return future.get(10, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            throw new IOException("swarm run failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("swarm run did not finish", e);
        }
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

    /**
     * @param peerBytes bytes the seeders actually served, which is the swarm's share of
     *                  the transfer and the number B0 has nothing to compare against
     */
    public record RunResult(
            String runId,
            double killFraction,
            String assetSha256,
            long assetBytes,
            long peerBytes,
            int peersDialled,
            int peersLost,
            long elapsedMillis
    ) {
    }

    public record Summary(String scenarioId, String baseline, long seed, List<RunResult> runs) {

        /** The P5-04 acceptance test: every run, churn or not, rebuilds the same bytes. */
        public boolean assetHashesMatch() {
            return runs.stream().map(RunResult::assetSha256).distinct().count() == 1;
        }

        public String assetSha256() {
            if (!assetHashesMatch()) {
                throw new IllegalStateException("runs disagree on the asset hash: " + runs);
            }
            return runs.getFirst().assetSha256();
        }

        public long totalPeerBytes() {
            return runs.stream().mapToLong(RunResult::peerBytes).sum();
        }
    }
}
