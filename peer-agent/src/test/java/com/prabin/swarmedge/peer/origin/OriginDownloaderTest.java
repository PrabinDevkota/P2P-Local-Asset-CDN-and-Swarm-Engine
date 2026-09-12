package com.prabin.swarmedge.peer.origin;

import com.prabin.swarmedge.manifest.AssetMaterializer;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.Ed25519Keys;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ManifestSigner;
import com.prabin.swarmedge.manifest.ManifestValidationException;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.origin.OriginHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2-02: origin baseline download. Origin is an untrusted byte source, so the
 * manifest hash decides what is kept, and a killed run resumes from the cache.
 */
class OriginDownloaderTest {

    private static final int CHUNK_SIZE = 64;
    private static final int TOTAL_CHUNKS = 5;
    private static final String ASSET_NAME = "game-x-1.4.0.bin";

    @TempDir
    Path tempDir;

    private Path originRoot;
    private Path storeRoot;
    private byte[] original;
    private ReleaseManifest manifest;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void publishAsset() throws Exception {
        originRoot = Files.createDirectories(tempDir.resolve("origin"));
        storeRoot = tempDir.resolve("peer-data");
        original = deterministicBytes(TOTAL_CHUNKS * CHUNK_SIZE);
        Path published = Files.write(originRoot.resolve(ASSET_NAME), original);

        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        KeyPair keys = Ed25519Keys.generate();
        manifest = ManifestSigner.sign(
                ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                        "2026-09-12T00:00:00Z", "2026-10-12T00:00:00Z", 1, "release-key-2026-01"),
                keys.getPrivate());
    }

    @Test
    void downloadsEveryChunkAndRebuildsTheOriginalFile() throws Exception {
        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            ChunkStore store = new ChunkStore(storeRoot);

            OriginDownloader.Result result = downloader(origin, settings(3)).download(manifest);

            assertThat(result.chunksDownloaded()).isEqualTo(TOTAL_CHUNKS);
            assertThat(result.chunksAlreadyCached()).isZero();
            assertThat(result.retries()).isZero();
            assertThat(result.bytesReceived()).isEqualTo(original.length);
            assertThat(result.bytesVerified()).isEqualTo(original.length);

            Path rebuilt = tempDir.resolve("rebuilt.bin");
            new AssetMaterializer(store).materialize(manifest.chunks(), rebuilt);
            assertThat(Files.readAllBytes(rebuilt)).containsExactly(original);
        }
    }

    @Test
    void aSecondRunPullsNoBytesBecauseEveryChunkIsCached() throws Exception {
        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            downloader(origin, settings(3)).download(manifest);

            OriginDownloader.Result again = downloader(origin, settings(3)).download(manifest);

            assertThat(again.chunksAlreadyCached()).isEqualTo(TOTAL_CHUNKS);
            assertThat(again.chunksDownloaded()).isZero();
            assertThat(again.bytesReceived()).isZero();
        }
    }

    @Test
    void aPartialRunResumesAndAsksOriginOnlyForWhatIsMissing() throws Exception {
        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            ChunkStore store = new ChunkStore(storeRoot);
            for (ChunkEntry chunk : manifest.chunks().subList(0, 2)) {
                byte[] slice = new byte[(int) chunk.length()];
                System.arraycopy(original, (int) chunk.offset(), slice, 0, slice.length);
                store.putVerified(chunk.sha256(), slice);
            }

            OriginDownloader.Result result = downloader(origin, settings(3)).download(manifest);

            assertThat(result.chunksAlreadyCached()).isEqualTo(2);
            assertThat(result.chunksDownloaded()).isEqualTo(TOTAL_CHUNKS - 2);
            assertThat(result.bytesReceived()).isEqualTo((TOTAL_CHUNKS - 2L) * CHUNK_SIZE);
        }
    }

    @Test
    void usesRangeRequestsSoTheWholeFileIsNeverPulledPerChunk() throws Exception {
        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            downloader(origin, settings(3).withRunId("run-1")).download(manifest);

            awaitServed(origin, "run-1", original.length);
            assertThat(origin.ledger().requests("run-1", ASSET_NAME)).isEqualTo(TOTAL_CHUNKS);
        }
    }

    @Test
    void tamperedOriginBytesFailClosedAfterRetries() throws Exception {
        byte[] tampered = original.clone();
        tampered[0] ^= 0xFF;
        Files.write(originRoot.resolve(ASSET_NAME), tampered);

        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            ChunkStore store = new ChunkStore(storeRoot);
            OriginDownloader downloader = downloader(origin, settings(3));

            assertThatThrownBy(() -> downloader.download(manifest))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("after 3 attempts");

            assertThat(store.contains(manifest.chunks().get(0).sha256())).isFalse();
            assertThat(sleeps).containsExactly(Duration.ofMillis(10), Duration.ofMillis(20));
        }
    }

    @Test
    void aMissingAssetAtOriginFailsClosed() throws Exception {
        Files.delete(originRoot.resolve(ASSET_NAME));

        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            OriginDownloader downloader = downloader(origin, settings(2));

            assertThatThrownBy(() -> downloader.download(manifest))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("after 2 attempts");
        }
    }

    @Test
    void refusesAnUnsignedManifest() throws Exception {
        ReleaseManifest unsigned = new ReleaseManifest(
                manifest.schemaVersion(), manifest.productId(), manifest.version(), manifest.fileName(),
                manifest.fileSize(), manifest.chunking(), manifest.chunks(), manifest.createdAt(),
                manifest.expiresAt(), manifest.sequence(), manifest.signingKeyId(), "");

        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            OriginDownloader downloader = downloader(origin, settings(1));

            assertThatThrownBy(() -> downloader.download(unsigned))
                    .isInstanceOf(ManifestValidationException.class)
                    .hasMessageContaining("signature required");
        }
    }

    private OriginDownloader downloader(OriginHttpServer origin, OriginDownloader.Settings settings)
            throws Exception {
        return new OriginDownloader(origin.baseUri(), new ChunkStore(storeRoot), settings, sleeps::add);
    }

    private static OriginDownloader.Settings settings(int maxAttempts) {
        return new OriginDownloader.Settings(maxAttempts, Duration.ofMillis(10), Duration.ofMillis(40),
                Duration.ofSeconds(2), Duration.ofSeconds(5), "");
    }

    private static void awaitServed(OriginHttpServer origin, String runId, long expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (origin.ledger().bytesForRun(runId) != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(origin.ledger().bytesForRun(runId)).isEqualTo(expected);
    }

    private static byte[] deterministicBytes(int size) {
        byte[] data = new byte[size];
        new Random(20260912L).nextBytes(data);
        return data;
    }
}
