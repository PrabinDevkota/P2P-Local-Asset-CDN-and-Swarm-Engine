package com.prabin.swarmedge.peer.net;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.manifest.AssetMaterializer;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.Ed25519Keys;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ManifestSigner;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.peer.chunk.ChunkAssembler;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.peer.session.BlockSender;
import com.prabin.swarmedge.peer.session.LeecherHandler;
import com.prabin.swarmedge.peer.session.PeerAuthPolicy;
import com.prabin.swarmedge.peer.session.SeederHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 exit gate: a real socket between a seeder and a leecher, with the manifest as
 * the only thing either side trusts. Covers conformance, corruption, bounds, refusal,
 * and resuming after a partial transfer.
 */
class TwoPeerTransferTest {

    private static final int CHUNK_SIZE = 4_096;
    private static final int BLOCK_SIZE = 1_024;
    private static final int FULL_CHUNKS = 5;
    private static final int TAIL_BYTES = 777;
    private static final String ASSET_NAME = "game-x-1.4.0.bin";
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @TempDir
    Path tempDir;

    private byte[] original;
    private ReleaseManifest manifest;
    private AssetId assetId;

    @BeforeEach
    void publishAsset() throws Exception {
        original = deterministicBytes(FULL_CHUNKS * CHUNK_SIZE + TAIL_BYTES);
        Path published = Files.write(tempDir.resolve(ASSET_NAME), original);
        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        KeyPair keys = Ed25519Keys.generate();
        manifest = ManifestSigner.sign(
                ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                        "2026-09-13T00:00:00Z", "2026-10-13T00:00:00Z", 1, "release-key-2026-01"),
                keys.getPrivate());
        assetId = AssetId.of(new byte[32]);
    }

    @ParameterizedTest
    @EnumSource(BlockSender.Mode.class)
    void aLeecherRebuildsTheAssetByteForByteFromASeeder(BlockSender.Mode mode) throws Exception {
        Peer seeder = seederWithEverything();
        Peer leecher = emptyPeer("leecher");

        try (SeederServer server = server(seeder, mode, PeerAuthPolicy.ACCEPT_ANY_TOKEN);
             LeecherClient client = new LeecherClient();
             ChunkAssembler assembler = assembler(leecher)) {

            LeecherHandler.Result result = await(client.fetch(server.address(), request(leecher, assembler)));

            assertThat(result.chunksStored()).isEqualTo(chunkCount());
            assertThat(result.bytesReceived()).isEqualTo(original.length);
            assertThat(result.hashMismatches()).isZero();
            assertThat(new ChunkInventory(manifest, leecher.store()).complete()).isTrue();

            Path rebuilt = tempDir.resolve("rebuilt-" + mode + ".bin");
            new AssetMaterializer(leecher.store()).materialize(manifest.chunks(), rebuilt);
            assertThat(Files.readAllBytes(rebuilt)).containsExactly(original);

            SeederHandler session = server.lastSession().orElseThrow();
            assertThat(session.bytesSent()).isEqualTo(original.length);
            assertThat(session.errorsSent()).isZero();
        }
    }

    @Test
    void aBlockIsSplitOutOfEveryChunkSoTheStreamingPathIsExercised() throws Exception {
        Peer seeder = seederWithEverything();
        Peer leecher = emptyPeer("leecher");

        try (SeederServer server = server(seeder, BlockSender.Mode.BUFFERED, PeerAuthPolicy.ACCEPT_ANY_TOKEN);
             LeecherClient client = new LeecherClient();
             ChunkAssembler assembler = assembler(leecher)) {

            LeecherHandler.Result result = await(client.fetch(server.address(), request(leecher, assembler)));

            // Four blocks per full chunk plus a short one for the tail.
            assertThat(result.blocksRequested()).isEqualTo(FULL_CHUNKS * (CHUNK_SIZE / BLOCK_SIZE) + 1);
            assertThat(server.lastSession().orElseThrow().blocksSent()).isEqualTo(result.blocksRequested());
        }
    }

    @Test
    void chunksAlreadyInTheCacheAreNeverAskedForAgain() throws Exception {
        Peer seeder = seederWithEverything();
        Peer leecher = emptyPeer("leecher");
        cacheChunks(leecher.store(), 0, 1, 2);

        try (SeederServer server = server(seeder, BlockSender.Mode.BUFFERED, PeerAuthPolicy.ACCEPT_ANY_TOKEN);
             LeecherClient client = new LeecherClient();
             ChunkAssembler assembler = assembler(leecher)) {

            LeecherHandler.Result result = await(client.fetch(server.address(), request(leecher, assembler)));

            assertThat(result.chunksStored()).isEqualTo(chunkCount() - 3);
            assertThat(result.bytesReceived()).isEqualTo(original.length - 3L * CHUNK_SIZE);
            assertThat(new ChunkInventory(manifest, leecher.store()).complete()).isTrue();
        }
    }

    @Test
    void aPartialTransferFinishesOnTheNextRun() throws Exception {
        Peer leecher = emptyPeer("leecher");

        // First run: the seeder only holds the first two chunks, so the transfer cannot finish.
        Peer partialSeeder = emptyPeer("seeder-partial");
        cacheChunks(partialSeeder.store(), 0, 1);
        try (SeederServer server = server(partialSeeder, BlockSender.Mode.BUFFERED, PeerAuthPolicy.ACCEPT_ANY_TOKEN);
             LeecherClient client = new LeecherClient();
             ChunkAssembler assembler = assembler(leecher)) {

            assertThatThrownBy(() -> await(client.fetch(server.address(), request(leecher, assembler))))
                    .hasMessageContaining("does not hold the remaining");
        }

        assertThat(new ChunkInventory(manifest, leecher.store()).missing()).containsExactly(2, 3, 4, 5);

        // Second run against a complete seeder picks up exactly what is left.
        Peer fullSeeder = seederWithEverything();
        try (SeederServer server = server(fullSeeder, BlockSender.Mode.BUFFERED, PeerAuthPolicy.ACCEPT_ANY_TOKEN);
             LeecherClient client = new LeecherClient();
             ChunkAssembler assembler = assembler(leecher)) {

            LeecherHandler.Result result = await(client.fetch(server.address(), request(leecher, assembler)));

            assertThat(result.chunksStored()).isEqualTo(4);
            assertThat(new ChunkInventory(manifest, leecher.store()).complete()).isTrue();
        }

        Path rebuilt = tempDir.resolve("rebuilt-resumed.bin");
        new AssetMaterializer(leecher.store()).materialize(manifest.chunks(), rebuilt);
        assertThat(Files.readAllBytes(rebuilt)).containsExactly(original);
    }

    @Test
    void aSeederServingTamperedBytesIsRejectedAndStoresNothing() throws Exception {
        Peer seeder = seederWithEverything();
        Peer leecher = emptyPeer("leecher");
        // Corrupt a chunk behind the store's back, the way bit rot or a hostile peer would.
        ChunkEntry victim = manifest.chunks().get(3);
        Path onDisk = seeder.store().pathFor(victim.sha256());
        byte[] rotten = Files.readAllBytes(onDisk);
        rotten[10] ^= 0xFF;
        Files.write(onDisk, rotten);

        try (SeederServer server = server(seeder, BlockSender.Mode.BUFFERED, PeerAuthPolicy.ACCEPT_ANY_TOKEN);
             LeecherClient client = new LeecherClient();
             ChunkAssembler assembler = assembler(leecher)) {

            assertThatThrownBy(() -> await(client.fetch(server.address(), request(leecher, assembler))))
                    .hasMessageContaining("failed verification");

            assertThat(leecher.store().contains(victim.sha256())).isFalse();
            assertThat(new ChunkInventory(manifest, leecher.store()).complete()).isFalse();
            // The rejected chunk leaves no staging file; other chunks may still be in flight.
            assertThat(stagingFiles(leecher))
                    .noneMatch(path -> path.getFileName().toString().equals("3.part"));
        }
    }

    @Test
    void aLeecherAskingForAnAssetTheSeederDoesNotServeIsRefused() throws Exception {
        Peer seeder = seederWithEverything();
        Peer leecher = emptyPeer("leecher");
        AssetId otherAsset = AssetId.of(filled((byte) 0x77, 32));

        try (SeederServer server = server(seeder, BlockSender.Mode.BUFFERED, PeerAuthPolicy.ACCEPT_ANY_TOKEN);
             LeecherClient client = new LeecherClient();
             ChunkAssembler assembler = assembler(leecher)) {

            LeecherClient.Request request = LeecherClient.Request.singlePeer(otherAsset, leecher.peerId(),
                    token(), new ChunkInventory(manifest, leecher.store()), assembler, settings(),
                    Duration.ofSeconds(5));

            assertThatThrownBy(() -> await(client.fetch(server.address(), request)))
                    .hasMessageContaining("refused the session, reason " + PeerAuthPolicy.UNKNOWN_ASSET);

            assertThat(server.lastSession().orElseThrow().bytesSent()).isZero();
        }
    }

    @Test
    void aSeederThatRefusesTokensNeverServesAByte() throws Exception {
        Peer seeder = seederWithEverything();
        Peer leecher = emptyPeer("leecher");

        try (SeederServer server = server(seeder, BlockSender.Mode.BUFFERED, PeerAuthPolicy.REFUSE_ALL);
             LeecherClient client = new LeecherClient();
             ChunkAssembler assembler = assembler(leecher)) {

            assertThatThrownBy(() -> await(client.fetch(server.address(), request(leecher, assembler))))
                    .hasMessageContaining("refused the session, reason " + PeerAuthPolicy.TOKEN_REJECTED);

            assertThat(server.lastSession().orElseThrow().bytesSent()).isZero();
            assertThat(new ChunkInventory(manifest, leecher.store()).missing()).hasSize(chunkCount());
        }
    }

    @Test
    void aSeederThatAcceptsAndThenSaysNothingDoesNotHangTheTransfer() throws Exception {
        Peer leecher = emptyPeer("leecher");

        // A listener that accepts the connection and never answers. The block timeout
        // cannot help here because no block was ever requested, so without a handshake
        // deadline this fetch would wait as long as the socket stayed open.
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             LeecherClient client = new LeecherClient();
             ChunkAssembler assembler = assembler(leecher)) {

            LeecherClient.Request request = LeecherClient.Request.singlePeer(assetId, leecher.peerId(), token(),
                    new ChunkInventory(manifest, leecher.store()), assembler,
                    new LeecherHandler.Settings(BLOCK_SIZE, 4, Duration.ofSeconds(10), 3,
                            Duration.ofMillis(300)),
                    Duration.ofSeconds(5));
            InetSocketAddress address =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), silent.getLocalPort());

            assertThatThrownBy(() -> await(client.fetch(address, request)))
                    .hasMessageContaining("did not finish the handshake")
                    .hasMessageContaining("HELLO_SENT");

            assertThat(new ChunkInventory(manifest, leecher.store()).missing()).hasSize(chunkCount());
        }
    }

    @Test
    void aLeecherWithNothingLeftToFetchFinishesWithoutAskingForAnything() throws Exception {
        Peer seeder = seederWithEverything();
        Peer leecher = emptyPeer("leecher");
        for (int i = 0; i < chunkCount(); i++) {
            cacheChunks(leecher.store(), i);
        }

        try (SeederServer server = server(seeder, BlockSender.Mode.BUFFERED, PeerAuthPolicy.ACCEPT_ANY_TOKEN);
             LeecherClient client = new LeecherClient();
             ChunkAssembler assembler = assembler(leecher)) {

            LeecherHandler.Result result = await(client.fetch(server.address(), request(leecher, assembler)));

            assertThat(result.blocksRequested()).isZero();
            assertThat(result.bytesReceived()).isZero();
            assertThat(server.lastSession().orElseThrow().bytesSent()).isZero();
        }
    }

    private Peer seederWithEverything() throws Exception {
        Peer seeder = emptyPeer("seeder");
        for (int i = 0; i < chunkCount(); i++) {
            cacheChunks(seeder.store(), i);
        }
        return seeder;
    }

    private Peer emptyPeer(String name) throws Exception {
        Path root = tempDir.resolve(name);
        ChunkStore store = new ChunkStore(root);
        return new Peer(root, store, PeerId.of(filled((byte) name.length(), 16)));
    }

    private ChunkAssembler assembler(Peer peer) throws Exception {
        return new ChunkAssembler(new ChunkInventory(manifest, peer.store()), peer.store(), peer.stagingDir());
    }

    private SeederServer server(Peer seeder, BlockSender.Mode mode, PeerAuthPolicy policy) throws Exception {
        SeederServer.Config config = new SeederServer.Config(0, assetId, seeder.peerId(),
                new ChunkInventory(manifest, seeder.store()), seeder.store(), mode, policy,
                BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE * 4, Duration.ofSeconds(10));
        return new SeederServer(config);
    }

    private LeecherClient.Request request(Peer leecher, ChunkAssembler assembler) {
        return LeecherClient.Request.singlePeer(assetId, leecher.peerId(), token(),
                new ChunkInventory(manifest, leecher.store()), assembler, settings(), Duration.ofSeconds(5));
    }

    private static LeecherHandler.Settings settings() {
        return new LeecherHandler.Settings(BLOCK_SIZE, 4, Duration.ofSeconds(10), 3, Duration.ofSeconds(10));
    }

    private static byte[] token() {
        return "phase-4-dev-token".getBytes(StandardCharsets.US_ASCII);
    }

    private int chunkCount() {
        return manifest.chunks().size();
    }

    private void cacheChunks(ChunkStore store, int... indexes) throws Exception {
        for (int index : indexes) {
            ChunkEntry chunk = manifest.chunks().get(index);
            store.putVerified(chunk.sha256(),
                    Arrays.copyOfRange(original, (int) chunk.offset(), (int) (chunk.offset() + chunk.length())));
        }
    }

    private static List<Path> stagingFiles(Peer peer) throws Exception {
        Path staging = peer.stagingDir();
        if (!Files.isDirectory(staging)) {
            return List.of();
        }
        try (var entries = Files.list(staging)) {
            return entries.toList();
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw asException(e.getCause());
        }
    }

    private static Exception asException(Throwable cause) {
        return cause instanceof Exception e ? e : new RuntimeException(cause);
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

    private record Peer(Path root, ChunkStore store, PeerId peerId) {

        Path stagingDir() {
            return root.resolve("staging").resolve("asset");
        }
    }
}
