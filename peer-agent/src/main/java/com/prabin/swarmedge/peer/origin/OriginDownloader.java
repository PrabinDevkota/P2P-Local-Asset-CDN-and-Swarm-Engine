package com.prabin.swarmedge.peer.origin;

import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.ManifestValidator;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Baseline B0: pull an asset from origin over HTTP, one chunk at a time.
 *
 * <p>Trust rule: the caller must already have run {@code ManifestVerifier} with a
 * trusted public key. Origin is an untrusted byte source like any peer, so every
 * chunk is hashed against the manifest before it is committed, and a mismatch is
 * retried rather than stored.
 *
 * <p>Resume is a cache lookup, not a retry: {@link ChunkStore#hasVerified} skips a
 * chunk that is already on disk and still verified, so a killed download restarts
 * where it stopped and a warm cache pulls no origin bytes at all (P7-01).
 */
public final class OriginDownloader {

    private static final Logger log = LoggerFactory.getLogger(OriginDownloader.class);

    private final URI baseUri;
    private final ChunkStore store;
    private final Settings settings;
    private final Sleeper sleeper;
    private final HttpClient http;

    public OriginDownloader(URI baseUri, ChunkStore store) {
        this(baseUri, store, Settings.defaults(), Thread::sleep);
    }

    public OriginDownloader(URI baseUri, ChunkStore store, Settings settings, Sleeper sleeper) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.store = Objects.requireNonNull(store, "store");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.http = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Result download(ReleaseManifest manifest) throws IOException, InterruptedException {
        Objects.requireNonNull(manifest, "manifest");
        ManifestValidator.validate(manifest);
        URI assetUri = fileUri(manifest.fileName());
        List<ChunkEntry> chunks = manifest.chunks();

        int downloaded = 0;
        int reused = 0;
        int retries = 0;
        long received = 0;
        long verified = 0;
        long reusedBytes = 0;
        for (ChunkEntry chunk : chunks) {
            if (store.hasVerified(chunk.sha256())) {
                reused++;
                reusedBytes += chunk.length();
                continue;
            }
            Attempt attempt = fetchVerified(assetUri, chunk);
            downloaded++;
            retries += attempt.retries();
            received += attempt.bytesReceived();
            verified += chunk.length();
        }
        log.info("origin download complete asset={} chunks={} reused={} retries={} bytes={}",
                manifest.fileName(), downloaded, reused, retries, received);
        return new Result(downloaded, reused, retries, received, verified, reusedBytes);
    }

    /** Retries bad bytes and transport errors alike; an untrusted source gets no benefit of the doubt. */
    private Attempt fetchVerified(URI assetUri, ChunkEntry chunk) throws IOException, InterruptedException {
        long bytesReceived = 0;
        Duration backoff = settings.initialBackoff();
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            try {
                byte[] body = requestRange(assetUri, chunk);
                bytesReceived += body.length;
                store.putVerified(chunk.sha256(), body);
                return new Attempt(attempt - 1, bytesReceived);
            } catch (IOException | IllegalArgumentException e) {
                lastFailure = e instanceof IOException io ? io : new IOException(e.getMessage(), e);
                log.warn("origin chunk {} attempt {}/{} failed: {}",
                        chunk.index(), attempt, settings.maxAttempts(), e.getMessage());
            }
            if (attempt < settings.maxAttempts()) {
                sleeper.sleep(backoff);
                backoff = nextBackoff(backoff);
            }
        }
        throw new IOException("origin failed chunk " + chunk.index()
                + " after " + settings.maxAttempts() + " attempts", lastFailure);
    }

    private byte[] requestRange(URI assetUri, ChunkEntry chunk) throws IOException, InterruptedException {
        long last = chunk.offset() + chunk.length() - 1;
        HttpRequest request = HttpRequest.newBuilder(assetUri)
                .GET()
                .timeout(settings.requestTimeout())
                .header("Range", "bytes=" + chunk.offset() + "-" + last)
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status != 206 && status != 200) {
            throw new IOException("origin returned HTTP " + status + " for chunk " + chunk.index());
        }
        byte[] body = response.body();
        if (body.length != chunk.length()) {
            throw new IOException("origin returned " + body.length + " bytes for chunk " + chunk.index()
                    + ", expected " + chunk.length());
        }
        return body;
    }

    private Duration nextBackoff(Duration current) {
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(settings.maxBackoff()) > 0 ? settings.maxBackoff() : doubled;
    }

    private URI fileUri(String fileName) {
        String name = Objects.requireNonNull(fileName, "fileName").trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("unsafe manifest fileName: " + fileName);
        }
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String query = settings.runId().isBlank() ? "" : "?runId=" + encode(settings.runId());
        return URI.create(base + "/files/" + encode(name) + query);
    }

    private static String encode(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }

    /** Every timeout and limit is configuration with a documented default (blueprint §21.2). */
    public record Settings(
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            Duration connectTimeout,
            Duration requestTimeout,
            String runId
    ) {
        public Settings {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
            Objects.requireNonNull(initialBackoff, "initialBackoff");
            Objects.requireNonNull(maxBackoff, "maxBackoff");
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            runId = runId == null ? "" : runId.trim();
        }

        public static Settings defaults() {
            return new Settings(3, Duration.ofMillis(200), Duration.ofSeconds(5),
                    Duration.ofSeconds(5), Duration.ofSeconds(60), "");
        }

        public Settings withRunId(String runId) {
            return new Settings(maxAttempts, initialBackoff, maxBackoff, connectTimeout, requestTimeout, runId);
        }
    }

    /**
     * @param bytesReceived includes bytes from failed attempts, because a wasted
     *                      retry still costs origin bandwidth
     * @param bytesReused   verified local bytes that satisfied a chunk with no GET
     */
    public record Result(
            int chunksDownloaded,
            int chunksAlreadyCached,
            int retries,
            long bytesReceived,
            long bytesVerified,
            long bytesReused
    ) {
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private record Attempt(int retries, long bytesReceived) {
    }
}
