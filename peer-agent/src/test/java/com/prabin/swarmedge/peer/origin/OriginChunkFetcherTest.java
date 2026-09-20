package com.prabin.swarmedge.peer.origin;

import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.Ed25519Keys;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ManifestSigner;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.origin.OriginHttpServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P8-03: an origin GET can be cancelled when a peer already has the chunk.
 */
class OriginChunkFetcherTest {

    private static final int CHUNK_SIZE = 64;
    private static final int TOTAL_CHUNKS = 3;
    private static final String ASSET_NAME = "game-x.bin";

    @TempDir
    Path tempDir;

    private Path originRoot;
    private Path storeRoot;
    private byte[] original;
    private ReleaseManifest manifest;

    @BeforeEach
    void publishAsset() throws Exception {
        originRoot = Files.createDirectories(tempDir.resolve("origin"));
        storeRoot = tempDir.resolve("store");
        original = new byte[TOTAL_CHUNKS * CHUNK_SIZE];
        new Random(20260920).nextBytes(original);
        Path published = Files.write(originRoot.resolve(ASSET_NAME), original);
        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        KeyPair keys = Ed25519Keys.generate();
        manifest = ManifestSigner.sign(
                ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                        "2026-09-12T00:00:00Z", "2026-10-12T00:00:00Z", 1, "release-key-2026-01"),
                keys.getPrivate());
    }

    @Test
    void aFetchedChunkLandsVerifiedInTheStore() throws Exception {
        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            ChunkStore store = new ChunkStore(storeRoot);
            OriginChunkFetcher fetcher = new OriginChunkFetcher(origin.baseUri(), ASSET_NAME, store, settings());

            assertThat(fetcher.fetch(manifest.chunks().get(0)).get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(store.hasVerified(manifest.chunks().get(0).sha256())).isTrue();
            assertThat(fetcher.bytesReceived()).isEqualTo(CHUNK_SIZE);
        }
    }

    @Test
    void cancelStopsAnInFlightGetAndStoresNothing() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer hang = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hang.createContext("/files/" + ASSET_NAME, exchange -> {
            entered.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        hang.start();
        try {
            ChunkStore store = new ChunkStore(storeRoot);
            URI base = URI.create("http://127.0.0.1:" + hang.getAddress().getPort());
            OriginChunkFetcher fetcher = new OriginChunkFetcher(base, ASSET_NAME, store,
                    new OriginDownloader.Settings(1, Duration.ofMillis(10), Duration.ofMillis(10),
                            Duration.ofSeconds(2), Duration.ofSeconds(15), "hang"));

            var pending = fetcher.fetch(manifest.chunks().get(0));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            fetcher.cancel(0);

            assertThat(pending.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(fetcher.cancelled()).isEqualTo(1);
            assertThat(store.contains(manifest.chunks().get(0).sha256())).isFalse();
        } finally {
            release.countDown();
            hang.stop(0);
        }
    }

    private static OriginDownloader.Settings settings() {
        return new OriginDownloader.Settings(1, Duration.ofMillis(10), Duration.ofMillis(10),
                Duration.ofSeconds(2), Duration.ofSeconds(5), "fetch-test");
    }
}
