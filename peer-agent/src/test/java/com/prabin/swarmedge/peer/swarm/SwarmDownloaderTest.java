package com.prabin.swarmedge.peer.swarm;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;
import com.prabin.swarmedge.manifest.AssetMaterializer;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.peer.chunk.ChunkAssembler;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.peer.laps.LapsWeights;
import com.prabin.swarmedge.peer.laps.PeerSelector;
import com.prabin.swarmedge.peer.net.SeederServer;
import com.prabin.swarmedge.peer.session.BlockSender;
import com.prabin.swarmedge.peer.session.LeecherHandler;
import com.prabin.swarmedge.peer.session.PeerAuthPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 exit gate: several peers, one asset, one shared queue.
 *
 * <p>Real sockets and real seeders, because the interesting failures are about how
 * sessions interleave: two peers must not fetch the same block, a chunk one peer supplies
 * must become visible to the others, and a peer that disappears mid-transfer must not
 * take the swarm with it.
 */
class SwarmDownloaderTest {

    private static final int CHUNK_SIZE = 4_096;
    private static final int BLOCK_SIZE = 1_024;
    private static final int FULL_CHUNKS = 7;
    private static final int TAIL_BYTES = 513;
    private static final String ASSET_NAME = "game-x-1.4.0.bin";
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final long SEED = 20260914L;

    @TempDir
    Path tempDir;

    private byte[] original;
    private ReleaseManifest manifest;
    private AssetId assetId;
    private final List<SeederServer> servers = new ArrayList<>();
    private final List<SwarmDownloader> swarms = new ArrayList<>();

    @BeforeEach
    void publishAsset() throws Exception {
        original = deterministicBytes(FULL_CHUNKS * CHUNK_SIZE + TAIL_BYTES);
        Path published = Files.write(tempDir.resolve(ASSET_NAME), original);
        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        manifest = ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                "2026-09-13T00:00:00Z", "2026-10-13T00:00:00Z", 1, "release-key-2026-01");
        assetId = AssetId.of(filled((byte) 0x11, 32));
    }

    @AfterEach
    void stopEverything() {
        swarms.forEach(SwarmDownloader::close);
        servers.forEach(SeederServer::close);
    }

    @Test
    void eightPeersEachHoldingOnePieceRebuildTheWholeAsset() throws Exception {
        // No peer has more than one chunk, so the asset can only be completed by using
        // all of them. A swarm that quietly leaned on one peer would fail this.
        List<InetSocketAddress> seeders = new ArrayList<>();
        for (int chunkIndex = 0; chunkIndex < chunkCount(); chunkIndex++) {
            seeders.add(seederHolding("seeder-" + chunkIndex, chunkIndex));
        }

        Leecher leecher = leecher("leecher");
        SwarmDownloader.Result result = await(swarm(leecher, chunkCount()).start(seeders));

        assertThat(result.peersLost()).isZero();
        assertThat(leecher.inventory().complete()).isTrue();
        assertThatRebuiltAssetMatches(leecher);
    }

    @Test
    void noTwoPeersAreAskedForTheSameBlock() throws Exception {
        List<InetSocketAddress> seeders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seeders.add(seederWithEverything("seeder-" + i));
        }

        Leecher leecher = leecher("leecher");
        await(swarm(leecher, 4).start(seeders));

        long blocksInTheAsset = blocksInTheAsset();
        long blocksServed = servers.stream()
                .mapToLong(server -> server.lastSession().map(session -> session.blocksSent()).orElse(0L))
                .sum();
        long bytesServed = servers.stream()
                .mapToLong(server -> server.lastSession().map(session -> session.bytesSent()).orElse(0L))
                .sum();

        assertThat(blocksServed).isEqualTo(blocksInTheAsset);
        assertThat(bytesServed).isEqualTo(original.length);
        assertThatRebuiltAssetMatches(leecher);
    }

    @Test
    void theWorkIsSpreadRatherThanTakenFromOnePeer() throws Exception {
        List<InetSocketAddress> seeders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seeders.add(seederWithEverything("seeder-" + i));
        }

        Leecher leecher = leecher("leecher");
        await(swarm(leecher, 4).start(seeders));

        long peersThatServedSomething = servers.stream()
                .filter(server -> server.lastSession().map(session -> session.blocksSent() > 0).orElse(false))
                .count();

        assertThat(peersThatServedSomething).isGreaterThan(1);
    }

    @Test
    void aChunkFetchedFromOnePeerIsAnnouncedToTheOthers() throws Exception {
        // Two seeders, each holding a different half. Both are leeching from us in the
        // sense that they track our inventory, so each must be told about the other's
        // chunks as they land.
        InetSocketAddress first = seederHolding("seeder-a", 0, 1, 2, 3);
        InetSocketAddress second = seederHolding("seeder-b", 4, 5, 6, 7);

        Leecher leecher = leecher("leecher");
        await(swarm(leecher, 2).start(List.of(first, second)));

        for (SeederServer server : servers) {
            // Every chunk we verified was announced to the peer that did not supply it.
            assertThat(server.lastSession().orElseThrow().havesReceived()).isPositive();
        }
        assertThat(leecher.inventory().complete()).isTrue();
    }

    @Test
    void aPeerThatDropsMidTransferHasItsBlocksPickedUpByTheRest() throws Exception {
        SeederServer doomed = server(seederStore("doomed", allChunks()), BlockSender.Mode.BUFFERED);
        List<InetSocketAddress> seeders = new ArrayList<>(List.of(doomed.address()));
        for (int i = 0; i < 3; i++) {
            seeders.add(seederWithEverything("survivor-" + i));
        }

        Leecher leecher = leecher("leecher");
        SwarmDownloader swarm = swarm(leecher, 4);
        CompletableFuture<SwarmDownloader.Result> asset = swarm.start(seeders);

        // Kill one peer while the transfer is in flight.
        doomed.close();

        SwarmDownloader.Result result = await(asset);

        assertThat(leecher.inventory().complete()).isTrue();
        assertThatRebuiltAssetMatches(leecher);
        assertThat(result.peersDialled()).isEqualTo(4);
    }

    @Test
    void aSwarmWhereEveryPeerDiesFailsRatherThanHangs() throws Exception {
        // Holds half the asset, so it can never finish the job on its own and the
        // outcome does not depend on how quickly it serves.
        SeederServer only = server(seederStore("only", 0, 1, 2, 3), BlockSender.Mode.BUFFERED);
        Leecher leecher = leecher("leecher");
        SwarmDownloader swarm = swarm(leecher, 1);

        CompletableFuture<SwarmDownloader.Result> asset = swarm.start(List.of(only.address()));
        only.close();

        assertThatThrownBy(() -> await(asset))
                .hasMessageContaining("ran out of peers");
        assertThat(leecher.inventory().complete()).isFalse();
    }

    @Test
    void aSpareCandidateReplacesAPeerThatRefusesUs() throws Exception {
        ChunkStore rudeStore = seederStore("rude", allChunks());
        SeederServer refuses = new SeederServer(new SeederServer.Config(0, assetId,
                PeerId.of(filled((byte) 9, 16)), new ChunkInventory(manifest, rudeStore),
                rudeStore, BlockSender.Mode.BUFFERED, PeerAuthPolicy.REFUSE_ALL,
                BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE * 4, Duration.ofSeconds(5)));
        servers.add(refuses);
        InetSocketAddress spare = seederWithEverything("spare");

        Leecher leecher = leecher("leecher");
        // Room for one session only, so the spare is used as a replacement.
        SwarmDownloader.Result result =
                await(swarm(leecher, 1).start(List.of(refuses.address(), spare)));

        assertThat(result.peersLost()).isEqualTo(1);
        assertThat(result.peersDialled()).isEqualTo(2);
        assertThatRebuiltAssetMatches(leecher);
    }

    @Test
    void aPeerHoldingNothingUsefulIsNotEnoughOnItsOwn() throws Exception {
        InetSocketAddress empty = seederHolding("empty");

        Leecher leecher = leecher("leecher");
        CompletableFuture<SwarmDownloader.Result> asset =
                swarm(leecher, 1, Duration.ofMillis(300), Duration.ofSeconds(1)).start(List.of(empty));

        // The peer is alive and answering, so nothing else would ever end this. The
        // swarm has to notice that a living peer is not a useful one.
        assertThatThrownBy(() -> await(asset))
                .hasMessageContaining("swarm stalled")
                .hasMessageContaining("no connected peer holds chunks");
        assertThat(leecher.inventory().complete()).isFalse();
    }

    @Test
    void theStallReportNamesTheChunksNobodyCouldSupply() throws Exception {
        // One peer, holding all but the last two chunks: the swarm gets most of the way
        // and then has nowhere to go, which is the case a bare timeout would not explain.
        int lastChunk = chunkCount() - 1;
        InetSocketAddress partial = seederHolding("partial",
                IntStream.range(0, lastChunk - 1).toArray());

        Leecher leecher = leecher("leecher");
        CompletableFuture<SwarmDownloader.Result> asset =
                swarm(leecher, 1, Duration.ofMillis(300), Duration.ofSeconds(1)).start(List.of(partial));

        assertThatThrownBy(() -> await(asset))
                .hasMessageContaining("no connected peer holds chunks ["
                        + (lastChunk - 1) + ", " + lastChunk + "]");
        // What it did manage to fetch is verified and kept; a stall is not a rollback.
        assertThat(leecher.inventory().missing()).containsExactly(lastChunk - 1, lastChunk);
    }

    @Test
    void aStallDeadlineShorterThanABlockTimeoutIsRejected() throws Exception {
        Leecher leecher = leecher("leecher");

        assertThatThrownBy(() -> SwarmDownloader.Settings.withoutEndgame(assetId, leecher.peerId(),
                token(), sessionSettings(Duration.ofSeconds(30)), Duration.ofSeconds(5), 4, SEED,
                Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be longer than the block timeout");
    }

    @Test
    void anEndgameSwarmRebuildsTheSameAssetAsOneWithout() throws Exception {
        // The endgame spends a duplicate block to stop the tail being decided by the
        // slowest peer. What it must not change is the result.
        List<InetSocketAddress> seeders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seeders.add(seederWithEverything("seeder-" + i));
        }

        Leecher leecher = leecher("leecher");
        SwarmDownloader.Settings settings = new SwarmDownloader.Settings(assetId, leecher.peerId(),
                token(), sessionSettings(Duration.ofSeconds(5)), Duration.ofSeconds(5), 4, SEED,
                Duration.ofSeconds(30), 8);

        await(swarm(leecher, settings).start(seeders));

        assertThat(leecher.inventory().complete()).isTrue();
        assertThatRebuiltAssetMatches(leecher);
    }

    @Test
    void anEndgameCostsDuplicateBlocksAndNeverDuplicateBytesOnDisk() throws Exception {
        List<InetSocketAddress> seeders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seeders.add(seederWithEverything("seeder-" + i));
        }

        Leecher leecher = leecher("leecher");
        // Threshold above the whole asset, so every block is eligible for a second
        // source: the most duplication the design permits.
        SwarmDownloader.Settings settings = new SwarmDownloader.Settings(assetId, leecher.peerId(),
                token(), sessionSettings(Duration.ofSeconds(5)), Duration.ofSeconds(5), 4, SEED,
                Duration.ofSeconds(30), (int) blocksInTheAsset() * 2);

        await(swarm(leecher, settings).start(seeders));

        long blocksServed = servers.stream()
                .mapToLong(server -> server.lastSession().map(session -> session.blocksSent()).orElse(0L))
                .sum();

        // Duplicates are extra, so the seeders may serve more than the asset...
        assertThat(blocksServed).isGreaterThanOrEqualTo(blocksInTheAsset());
        // ...but never more than two copies of it, which is the §8.3 ceiling.
        assertThat(blocksServed).isLessThanOrEqualTo(2 * blocksInTheAsset());
        // ...and the asset on disk is the asset, not two of it.
        assertThatRebuiltAssetMatches(leecher);
    }

    @Test
    void aSlowSwarmIsNotMistakenForADeadOne() throws Exception {
        // Each peer holds one chunk, so completion needs every one of them and the run
        // takes a while. The stall deadline is short but progress keeps resetting it.
        List<InetSocketAddress> seeders = new ArrayList<>();
        for (int chunkIndex = 0; chunkIndex < chunkCount(); chunkIndex++) {
            seeders.add(seederHolding("seeder-" + chunkIndex, chunkIndex));
        }

        Leecher leecher = leecher("leecher");
        await(swarm(leecher, chunkCount(), Duration.ofMillis(500), Duration.ofSeconds(2))
                .start(seeders));

        assertThat(leecher.inventory().complete()).isTrue();
        assertThatRebuiltAssetMatches(leecher);
    }

    @Test
    void aLeecherThatAlreadyHasEverythingFinishesWithoutDiallingAnyone() throws Exception {
        Leecher leecher = leecher("leecher");
        for (ChunkEntry chunk : manifest.chunks()) {
            leecher.store().putVerified(chunk.sha256(), bytesOf(chunk));
        }
        leecher.inventory().rescan();

        SwarmDownloader.Result result = await(swarm(leecher, 8).start(List.of()));

        assertThat(result.peersDialled()).isZero();
        assertThat(leecher.inventory().complete()).isTrue();
    }

    @Test
    void aSwarmWithNoCandidatesFailsImmediately() throws Exception {
        Leecher leecher = leecher("leecher");

        assertThatThrownBy(() -> await(swarm(leecher, 8).start(List.of())))
                .hasMessageContaining("no candidate peers");
    }

    @Test
    void aSwarmCannotBeStartedTwice() throws Exception {
        InetSocketAddress seeder = seederWithEverything("seeder");
        Leecher leecher = leecher("leecher");
        SwarmDownloader swarm = swarm(leecher, 2);

        await(swarm.start(List.of(seeder)));

        assertThatThrownBy(() -> swarm.start(List.of(seeder)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been started");
    }

    @Test
    void aPoisonedSeederDoesNotStopTheSwarmFetchingTheChunkFromSomeoneElse() throws Exception {
        // Two peers hold every chunk. One has had a file flipped behind the store's back.
        // A swarm that blamed whoever delivered the last block would sometimes kill the
        // honest peer and keep the liar; it has to rebuild the chunk and ask again.
        InetSocketAddress honest = seederWithEverything("honest");
        ChunkStore rottenStore = seederStore("rotten", allChunks());
        ChunkEntry victim = manifest.chunks().get(0);
        byte[] rotten = Files.readAllBytes(rottenStore.pathFor(victim.sha256()));
        rotten[10] ^= 0xFF;
        Files.write(rottenStore.pathFor(victim.sha256()), rotten);
        InetSocketAddress liar = server(rottenStore, BlockSender.Mode.BUFFERED).address();

        Leecher leecher = leecher("leecher");
        await(swarm(leecher, 2).start(List.of(honest, liar)));

        assertThat(leecher.inventory().complete()).isTrue();
        assertThat(leecher.store().contains(victim.sha256())).isTrue();
        assertThatRebuiltAssetMatches(leecher);
    }

    @Test
    void aLiveSwarmRecordsTheBlocksItObservedIntoPeerMetrics() throws Exception {
        Locality here = new Locality("hq", "floor-2");
        PeerSelector selector = new PeerSelector(LapsWeights.defaults(), here, SEED);
        List<PeerSelector.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            PeerId id = PeerId.of(filled((byte) (20 + i), 16));
            InetSocketAddress address = server(seederStore("metrics-" + i, allChunks()),
                    BlockSender.Mode.BUFFERED, id).address();
            candidates.add(PeerSelector.Candidate.of(address, id, here));
        }

        Leecher leecher = leecher("leecher");
        SwarmDownloader swarm = new SwarmDownloader(
                SwarmDownloader.Settings.withoutEndgame(assetId, leecher.peerId(), token(),
                        sessionSettings(Duration.ofSeconds(5)), Duration.ofSeconds(5), 2, SEED,
                        Duration.ofSeconds(30)),
                leecher.inventory(), leecher.assembler(), selector);
        swarms.add(swarm);
        await(swarm.startPreferring(candidates));

        long observed = 0;
        for (PeerSelector.Candidate candidate : candidates) {
            observed += selector.metricsFor(candidate.peerId()).blocksCompleted();
        }
        assertThat(observed).isEqualTo(blocksInTheAsset());
        assertThatRebuiltAssetMatches(leecher);
    }

    private SwarmDownloader swarm(Leecher leecher, int maxPeers) {
        // Generous stall deadline: these runs are meant to finish, not to trip it.
        return swarm(leecher, maxPeers, Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    private SwarmDownloader swarm(Leecher leecher, int maxPeers, Duration blockTimeout,
                                  Duration stallTimeout) {
        // These are the Phase 5 cases, so no endgame: exactly one source per block.
        SwarmDownloader.Settings settings = SwarmDownloader.Settings.withoutEndgame(assetId,
                leecher.peerId(), token(), sessionSettings(blockTimeout), Duration.ofSeconds(5),
                maxPeers, SEED, stallTimeout);
        return swarm(leecher, settings);
    }

    private SwarmDownloader swarm(Leecher leecher, SwarmDownloader.Settings settings) {
        SwarmDownloader swarm = new SwarmDownloader(settings, leecher.inventory(), leecher.assembler());
        swarms.add(swarm);
        return swarm;
    }

    private static LeecherHandler.Settings sessionSettings(Duration blockTimeout) {
        // Eight outstanding requests per peer is the blueprint's B1 pipeline depth.
        return new LeecherHandler.Settings(BLOCK_SIZE, 8, blockTimeout, 3, Duration.ofSeconds(5));
    }

    private Leecher leecher(String name) throws Exception {
        Path root = tempDir.resolve(name);
        ChunkStore store = new ChunkStore(root);
        ChunkInventory inventory = new ChunkInventory(manifest, store);
        ChunkAssembler assembler = new ChunkAssembler(inventory, store, root.resolve("staging"));
        return new Leecher(root, store, inventory, assembler, PeerId.of(filled((byte) name.length(), 16)));
    }

    private InetSocketAddress seederWithEverything(String name) throws Exception {
        return server(seederStore(name, allChunks()), BlockSender.Mode.FILE_REGION).address();
    }

    private InetSocketAddress seederHolding(String name, int... chunkIndexes) throws Exception {
        return server(seederStore(name, chunkIndexes), BlockSender.Mode.BUFFERED).address();
    }

    private ChunkStore seederStore(String name, int... chunkIndexes) throws Exception {
        ChunkStore store = new ChunkStore(tempDir.resolve(name));
        for (int chunkIndex : chunkIndexes) {
            ChunkEntry chunk = manifest.chunks().get(chunkIndex);
            store.putVerified(chunk.sha256(), bytesOf(chunk));
        }
        return store;
    }

    private SeederServer server(ChunkStore store, BlockSender.Mode mode) throws Exception {
        return server(store, mode, PeerId.of(filled((byte) 7, 16)));
    }

    private SeederServer server(ChunkStore store, BlockSender.Mode mode, PeerId peerId) throws Exception {
        SeederServer server = new SeederServer(SeederServer.Config.of(assetId,
                peerId, new ChunkInventory(manifest, store), store, mode, BLOCK_SIZE));
        servers.add(server);
        return server;
    }

    private void assertThatRebuiltAssetMatches(Leecher leecher) throws Exception {
        Path rebuilt = leecher.root().resolve("rebuilt.bin");
        new AssetMaterializer(leecher.store()).materialize(manifest.chunks(), rebuilt);
        assertThat(sha256(Files.readAllBytes(rebuilt))).isEqualTo(sha256(original));
    }

    private int[] allChunks() {
        int[] all = new int[chunkCount()];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return all;
    }

    private int chunkCount() {
        return manifest.chunks().size();
    }

    private long blocksInTheAsset() {
        long blocks = 0;
        for (ChunkEntry chunk : manifest.chunks()) {
            blocks += (chunk.length() + BLOCK_SIZE - 1) / BLOCK_SIZE;
        }
        return blocks;
    }

    private byte[] bytesOf(ChunkEntry chunk) {
        return Arrays.copyOfRange(original, (int) chunk.offset(), (int) (chunk.offset() + chunk.length()));
    }

    private static byte[] token() {
        return "phase-5-dev-token".getBytes(StandardCharsets.US_ASCII);
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
        new Random(20260913).nextBytes(out);
        return out;
    }

    private record Leecher(Path root, ChunkStore store, ChunkInventory inventory,
                           ChunkAssembler assembler, PeerId peerId) {
    }
}
