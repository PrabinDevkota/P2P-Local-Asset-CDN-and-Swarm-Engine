package com.prabin.swarmedge.peer.fallback;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;
import com.prabin.swarmedge.manifest.AssetMaterializer;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.origin.OriginHttpServer;
import com.sun.net.httpserver.HttpServer;
import com.prabin.swarmedge.peer.chunk.ChunkAssembler;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.peer.laps.LapsWeights;
import com.prabin.swarmedge.peer.laps.PeerSelector;
import com.prabin.swarmedge.peer.net.SeederServer;
import com.prabin.swarmedge.peer.origin.OriginChunkFetcher;
import com.prabin.swarmedge.peer.origin.OriginDownloader;
import com.prabin.swarmedge.peer.session.BlockSender;
import com.prabin.swarmedge.peer.session.LeecherHandler;
import com.prabin.swarmedge.peer.swarm.SwarmDownloader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P8-03: cache and local peers first, then EDGE, then limited origin. First verified
 * chunk wins, and the other source is cancelled.
 */
class HybridDownloaderTest {

    private static final int CHUNK_SIZE = 1_024;
    private static final int BLOCK_SIZE = 256;
    private static final int FULL_CHUNKS = 4;
    private static final String ASSET_NAME = "game-x.bin";
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final long SEED = 20260920L;

    @TempDir
    Path tempDir;

    private byte[] original;
    private ReleaseManifest manifest;
    private AssetId assetId;
    private Path originRoot;
    private final List<SeederServer> servers = new ArrayList<>();
    private final List<AutoCloseable> opened = new ArrayList<>();

    @BeforeEach
    void publishAsset() throws Exception {
        original = deterministicBytes(FULL_CHUNKS * CHUNK_SIZE);
        originRoot = Files.createDirectories(tempDir.resolve("origin"));
        Path published = Files.write(originRoot.resolve(ASSET_NAME), original);
        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        manifest = ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                "2026-09-13T00:00:00Z", "2026-10-13T00:00:00Z", 1, "release-key-2026-01");
        assetId = AssetId.of(filled((byte) 0x11, 32));
    }

    @AfterEach
    void stopEverything() {
        for (AutoCloseable closeable : opened) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // tests already observed the outcome they care about
            }
        }
        servers.forEach(SeederServer::close);
    }

    @Test
    void originFillsChunksNoConnectedPeerHolds() throws Exception {
        Locality here = new Locality("hq", "floor-2");
        InetSocketAddress empty = seederHolding("empty", here);
        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            Hybrid hybrid = hybrid(here, origin,
                    new FallbackPolicy(Duration.ZERO, Duration.ZERO, Duration.ZERO, 2, 0.5));

            HybridDownloader.Result result = await(hybrid.downloader().start(List.of(
                    PeerSelector.Candidate.of(empty, PeerId.of(filled((byte) 2, 16)), here))));

            assertThat(hybrid.leecher().inventory().complete()).isTrue();
            assertThat(result.originChunks()).isEqualTo(FULL_CHUNKS);
            assertThat(result.originBytes()).isEqualTo(original.length);
            assertThatRebuiltAssetMatches(hybrid.leecher());
        }
    }

    @Test
    void aSwarmHitCancelsTheOriginGetForThatChunk() throws Exception {
        Locality here = new Locality("hq", "floor-2");
        InetSocketAddress full = seederWithEverything("full", here);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer hang = hangingOrigin(release);
        try {
            URI base = URI.create("http://127.0.0.1:" + hang.getAddress().getPort());
            Hybrid hybrid = hybrid(here, base,
                    new FallbackPolicy(Duration.ZERO, Duration.ZERO, Duration.ZERO, 2, 0.5),
                    List.of(), 2);

            HybridDownloader.Result result = await(hybrid.downloader().start(List.of(
                    PeerSelector.Candidate.of(full, PeerId.of(filled((byte) 3, 16)), here))));

            assertThat(hybrid.leecher().inventory().complete()).isTrue();
            assertThat(result.originChunks()).isZero();
            assertThat(result.originCancelled()).isPositive();
            assertThatRebuiltAssetMatches(hybrid.leecher());
        } finally {
            release.countDown();
            hang.stop(0);
        }
    }

    @Test
    void edgeIsOfferedWhenThePeerPipelineIsStillEmpty() throws Exception {
        Locality here = new Locality("hq", "floor-2");
        InetSocketAddress empty = seederHolding("local-empty", here);
        InetSocketAddress edgeAddress = seederWithEverything("edge", here);
        PeerSelector.Candidate edge = PeerSelector.Candidate.edge(
                edgeAddress, PeerId.of(filled((byte) 9, 16)), here);

        Hybrid hybrid = hybrid(here, (URI) null,
                new FallbackPolicy(Duration.ZERO, Duration.ofSeconds(30), Duration.ZERO, 2, 0.5),
                List.of(edge), 2);

        HybridDownloader.Result result = await(hybrid.downloader().start(List.of(
                PeerSelector.Candidate.of(empty, PeerId.of(filled((byte) 4, 16)), here))));

        assertThat(result.edgeOffered()).isTrue();
        assertThat(hybrid.leecher().inventory().complete()).isTrue();
        assertThat(servers.get(1).lastSession().map(session -> session.bytesSent()).orElse(0L))
                .isPositive();
        assertThatRebuiltAssetMatches(hybrid.leecher());
    }

    private Hybrid hybrid(Locality here, OriginHttpServer origin, FallbackPolicy policy) throws Exception {
        return hybrid(here, origin == null ? null : origin.baseUri(), policy, List.of(), 2);
    }

    private Hybrid hybrid(Locality here, URI originBase, FallbackPolicy policy,
                          List<PeerSelector.Candidate> edges, int maxPeers) throws Exception {
        Path root = tempDir.resolve("leecher-" + opened.size());
        ChunkStore store = new ChunkStore(root);
        ChunkInventory inventory = new ChunkInventory(manifest, store);
        ChunkAssembler assembler = new ChunkAssembler(inventory, store, root.resolve("staging"));
        PeerSelector selector = new PeerSelector(LapsWeights.defaults(), here, SEED);
        SwarmDownloader swarm = new SwarmDownloader(
                SwarmDownloader.Settings.withoutEndgame(assetId, PeerId.of(filled((byte) 1, 16)),
                        token(), sessionSettings(), Duration.ofSeconds(5), maxPeers, SEED,
                        Duration.ofSeconds(30)),
                inventory, assembler, selector);
        OriginChunkFetcher fetcher = originBase == null ? null
                : new OriginChunkFetcher(originBase, ASSET_NAME, store, originSettings());
        HybridDownloader downloader = new HybridDownloader(swarm, fetcher, inventory, policy, SEED, edges);
        opened.add(downloader);
        opened.add(swarm);
        opened.add(assembler);
        return new Hybrid(downloader, new Leecher(root, store, inventory, assembler));
    }

    private HttpServer hangingOrigin(CountDownLatch release) throws Exception {
        HttpServer hang = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hang.createContext("/files/" + ASSET_NAME, exchange -> {
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        hang.start();
        return hang;
    }

    private InetSocketAddress seederWithEverything(String name, Locality locality) throws Exception {
        return seederHolding(name, locality, allChunks());
    }

    private InetSocketAddress seederHolding(String name, Locality locality, int... chunkIndexes) throws Exception {
        ChunkStore store = new ChunkStore(tempDir.resolve(name));
        for (int chunkIndex : chunkIndexes) {
            ChunkEntry chunk = manifest.chunks().get(chunkIndex);
            store.putVerified(chunk.sha256(), bytesOf(chunk));
        }
        SeederServer server = new SeederServer(SeederServer.Config.of(assetId,
                PeerId.of(filled((byte) (10 + servers.size()), 16)),
                new ChunkInventory(manifest, store), store, BlockSender.Mode.BUFFERED, BLOCK_SIZE));
        servers.add(server);
        return server.address();
    }

    private void assertThatRebuiltAssetMatches(Leecher leecher) throws Exception {
        Path rebuilt = leecher.root().resolve("rebuilt.bin");
        new AssetMaterializer(leecher.store()).materialize(manifest.chunks(), rebuilt);
        assertThat(sha256(Files.readAllBytes(rebuilt))).isEqualTo(sha256(original));
    }

    private int[] allChunks() {
        int[] all = new int[manifest.chunks().size()];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return all;
    }

    private byte[] bytesOf(ChunkEntry chunk) {
        return Arrays.copyOfRange(original, (int) chunk.offset(), (int) (chunk.offset() + chunk.length()));
    }

    private static LeecherHandler.Settings sessionSettings() {
        return new LeecherHandler.Settings(BLOCK_SIZE, 8, Duration.ofSeconds(5), 3, Duration.ofSeconds(5));
    }

    private static OriginDownloader.Settings originSettings() {
        return new OriginDownloader.Settings(1, Duration.ofMillis(10), Duration.ofMillis(10),
                Duration.ofSeconds(2), Duration.ofSeconds(10), "hybrid");
    }

    private static byte[] token() {
        return "phase-8-dev-token".getBytes(StandardCharsets.US_ASCII);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : new RuntimeException(e.getCause());
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] filled(byte value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, value);
        return out;
    }

    private static byte[] deterministicBytes(int length) {
        byte[] out = new byte[length];
        new Random(20260920).nextBytes(out);
        return out;
    }

    private record Hybrid(HybridDownloader downloader, Leecher leecher) {
    }

    private record Leecher(Path root, ChunkStore store, ChunkInventory inventory, ChunkAssembler assembler) {
    }
}
