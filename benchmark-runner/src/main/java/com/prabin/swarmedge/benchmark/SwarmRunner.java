package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.Hex;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;
import com.prabin.swarmedge.manifest.AssetMaterializer;
import com.prabin.swarmedge.manifest.ChunkCache;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.ReleaseFreshness;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.origin.OriginByteLedger;
import com.prabin.swarmedge.peer.chunk.ChunkAssembler;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.peer.fallback.HybridDownloader;
import com.prabin.swarmedge.peer.laps.PeerSelector;
import com.prabin.swarmedge.peer.net.SeederServer;
import com.prabin.swarmedge.peer.origin.OriginChunkFetcher;
import com.prabin.swarmedge.peer.origin.OriginDownloader;
import com.prabin.swarmedge.peer.session.BlockSender;
import com.prabin.swarmedge.peer.session.LeecherHandler;
import com.prabin.swarmedge.peer.session.PeerAuthPolicy;
import com.prabin.swarmedge.peer.session.SeederHandler;
import com.prabin.swarmedge.peer.swarm.SwarmDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Baselines B1–B3 and B6: rebuild one asset from a swarm and record what each run cost.
 *
 * <p>Which baseline it is comes entirely from the scenario's {@code scheduler} section.
 * The runner is the same code in all three cases, which is the point — if each baseline
 * had its own runner, a difference in the numbers could be a difference in the harness.
 *
 * <p>Seeders here are all on loopback, so their locality labels are assigned by the
 * scenario rather than discovered from a tracker: the agent has no announce loop yet. A
 * B2 or B3 run therefore exercises the source policy, not discovery.
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
public final class SwarmRunner {

    private static final Logger log = LoggerFactory.getLogger(SwarmRunner.class);

    private final SwarmScenarioConfig config;
    private final Path workDir;
    private final Path sourceFile;
    private final AssetId assetId;
    private final URI originBase;
    private final OriginByteLedger originLedger;
    private PeerSelector selector;

    /**
     * @param sourceFile the published asset, used to stock the seeders; a real swarm
     *                   would have fetched these chunks from origin first
     */
    public SwarmRunner(SwarmScenarioConfig config, Path workDir, Path sourceFile, AssetId assetId) {
        this(config, workDir, sourceFile, assetId, null, null);
    }

    /**
     * B6 path: origin Range GETs are billed on {@code originLedger} under each run id.
     * B1–B3 callers omit both; a missing {@code fallback} section keeps today's path.
     */
    public SwarmRunner(SwarmScenarioConfig config, Path workDir, Path sourceFile, AssetId assetId,
                       URI originBase, OriginByteLedger originLedger) {
        this.config = Objects.requireNonNull(config, "config");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
        this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile");
        this.assetId = Objects.requireNonNull(assetId, "assetId");
        this.originBase = originBase;
        this.originLedger = originLedger;
    }

    /** The manifest must already be signature-verified by the caller. Freshness is checked here. */
    public Summary run(ReleaseManifest manifest) throws IOException, InterruptedException {
        Objects.requireNonNull(manifest, "manifest");
        new ReleaseFreshness(Clock.systemUTC()).accept(manifest);
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
        List<SeederServer> edges = new ArrayList<>();
        try {
            for (int i = 0; i < config.seederCount(); i++) {
                seeders.add(startSeeder(manifest, runId, i));
            }
            for (int i = 0; i < config.edgeCount(); i++) {
                edges.add(startEdge(manifest, runId, i));
            }
            List<PeerSelector.Candidate> candidates = new ArrayList<>();
            for (int i = 0; i < seeders.size(); i++) {
                candidates.add(PeerSelector.Candidate.of(
                        seeders.get(i).address(), seederPeerId(i), seederLocality(i)));
            }
            List<PeerSelector.Candidate> edgeCandidates = new ArrayList<>();
            for (int i = 0; i < edges.size(); i++) {
                edgeCandidates.add(PeerSelector.Candidate.edge(
                        edges.get(i).address(), edgePeerId(i), config.edgeLocality()));
            }

            try (ChunkCache cache = ChunkCache.open(storeRoot)) {
                ChunkStore store = cache.store();
                long cacheBytes = cacheBytes(manifest, store);
                ChunkInventory inventory = new ChunkInventory(manifest, store);
                Path output = workDir.resolve(runId).resolve(manifest.fileName());

                long startedAt = System.nanoTime();
                SwarmDownloader.Result result;
                PeerSelector selector = selectorOrNull();
                try (ChunkAssembler assembler = new ChunkAssembler(inventory, store,
                        workDir.resolve(runId).resolve("staging"));
                     SwarmDownloader swarm = new SwarmDownloader(swarmSettings(), inventory, assembler, selector)) {

                    if (config.fallback() != null) {
                        OriginChunkFetcher fetcher = originBase == null ? null
                                : new OriginChunkFetcher(originBase, manifest.fileName(), store,
                                OriginDownloader.Settings.defaults().withRunId(runId));
                        try (HybridDownloader hybrid = new HybridDownloader(swarm, fetcher, inventory,
                                config.fallback(), config.seed() + runIndex, edgeCandidates)) {
                            var asset = hybrid.start(candidates);
                            killPeers(seeders, killFraction, runIndex);
                            result = await(asset).swarm();
                        }
                    } else {
                        var asset = selector == null
                                ? swarm.start(candidates.stream().map(PeerSelector.Candidate::address).toList())
                                : swarm.startPreferring(candidates);
                        killPeers(seeders, killFraction, runIndex);
                        result = await(asset);
                    }
                }
                new AssetMaterializer(store).materialize(manifest.chunks(), output);
                long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

                long peerBytes = servedBytes(seeders);
                long edgeBytes = servedBytes(edges);
                long originBytes = originLedger == null ? 0L : originLedger.bytes(runId, manifest.fileName());
                long originPeakBytes = originLedger == null ? 0L : originLedger.peakBytes(runId, manifest.fileName());
                RunResult run = new RunResult(runId, killFraction, sha256OfFile(output), Files.size(output),
                        peerBytes, result.peersDialled(), result.peersLost(), elapsedMillis,
                        originBytes, originPeakBytes, edgeBytes, cacheBytes);
                log.info("{} run {} asset={} peerBytes={} edgeBytes={} originBytes={} cacheBytes={} peersLost={} elapsedMs={}",
                        config.baseline(), runId, run.assetSha256(), run.peerBytes(), run.edgeBytes(),
                        run.originBytes(), run.cacheBytes(), run.peersLost(), run.elapsedMillis());
                return run;
            }
        } finally {
            edges.forEach(SeederServer::close);
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

    /** Same binary as a desktop seeder; the upload budget and Candidate role are the EDGE. */
    private SeederServer startEdge(ReleaseManifest manifest, String runId, int index) throws IOException {
        Path root = workDir.resolve(runId).resolve("edge-" + index);
        ChunkStore store = new ChunkStore(root);
        stockAll(manifest, store);
        ChunkInventory inventory = new ChunkInventory(manifest, store);
        return new SeederServer(new SeederServer.Config(0, assetId, edgePeerId(index), inventory, store,
                BlockSender.Mode.FILE_REGION, PeerAuthPolicy.ACCEPT_ANY_TOKEN, config.blockSizeBytes(),
                config.blockSizeBytes(), config.blockSizeBytes() * 4, config.handshakeTimeout(),
                SeederHandler.Settings.edge(config.edgeUploadBytesPerSecond())));
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

    private void stockAll(ReleaseManifest manifest, ChunkStore store) throws IOException {
        byte[] asset = Files.readAllBytes(sourceFile);
        for (ChunkEntry chunk : manifest.chunks()) {
            byte[] bytes = new byte[(int) chunk.length()];
            System.arraycopy(asset, (int) chunk.offset(), bytes, 0, bytes.length);
            store.putVerified(chunk.sha256(), bytes);
        }
    }

    private static long servedBytes(List<SeederServer> servers) {
        return servers.stream()
                .mapToLong(server -> server.lastSession().map(session -> session.bytesSent()).orElse(0L))
                .sum();
    }

    private static long cacheBytes(ReleaseManifest manifest, ChunkStore store) throws IOException {
        long cached = 0L;
        for (ChunkEntry chunk : manifest.chunks()) {
            if (store.hasVerified(chunk.sha256())) {
                cached += chunk.length();
            }
        }
        return cached;
    }

    private SwarmDownloader.Settings swarmSettings() {
        LeecherHandler.Settings session = new LeecherHandler.Settings(
                config.blockSizeBytes(),
                config.outstandingRequestsPerPeer(),
                config.blockTimeout(),
                config.maxAttemptsPerBlock(),
                config.handshakeTimeout());
        return new SwarmDownloader.Settings(assetId, leecherPeerId(), token(), session,
                config.connectTimeout(), config.maxPeers(), config.seed(), config.stallTimeout(),
                config.endgameThresholdBlocks());
    }

    private PeerSelector selectorOrNull() {
        if (config.sourcePolicy() == SwarmScenarioConfig.SourcePolicy.AS_DISCOVERED) {
            return null;
        }
        if (selector == null) {
            selector = new PeerSelector(config.lapsWeights(), config.leecherLocality(), config.seed());
        }
        return selector;
    }

    private Locality seederLocality(int index) {
        if (config.seederLocalities().isEmpty()) {
            return new Locality("lab", "default");
        }
        return config.seederLocalities().get(index);
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

    private static PeerId edgePeerId(int index) {
        byte[] bits = new byte[16];
        bits[0] = 0x03;
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
     * @param peerBytes        bytes desktop seeders actually served
     * @param originBytes      origin Range bytes billed to this run, or 0 on B1–B3
     * @param originPeakBytes  busiest one-second origin window, or 0 on B1–B3
     * @param edgeBytes        bytes the site EDGE served, or 0 when none ran
     * @param cacheBytes       verified local bytes already present before this run
     */
    public record RunResult(
            String runId,
            double killFraction,
            String assetSha256,
            long assetBytes,
            long peerBytes,
            int peersDialled,
            int peersLost,
            long elapsedMillis,
            long originBytes,
            long originPeakBytes,
            long edgeBytes,
            long cacheBytes
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
