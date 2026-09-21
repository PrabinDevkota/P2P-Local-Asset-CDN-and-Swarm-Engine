package com.prabin.swarmedge.peer.origin;

import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One cancellable HTTP Range GET per missing chunk (P8-03).
 *
 * <p>Origin stays chunk-granular. When a peer verifies the same chunk first, {@link #cancel}
 * aborts the GET so the flash crowd does not keep paying for bytes nobody will store.
 * Bytes that already arrived after a cancel are discarded: they were not verified into
 * the store, and they must not be.
 */
public final class OriginChunkFetcher {

    private static final Logger log = LoggerFactory.getLogger(OriginChunkFetcher.class);

    private final URI baseUri;
    private final String fileName;
    private final ChunkStore store;
    private final OriginDownloader.Settings settings;
    private final HttpClient http;
    private final ConcurrentHashMap<Integer, Flight> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicInteger cancelled = new AtomicInteger();

    public OriginChunkFetcher(URI baseUri, String fileName, ChunkStore store,
                              OriginDownloader.Settings settings) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.fileName = requireFileName(fileName);
        this.store = Objects.requireNonNull(store, "store");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.http = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Fetch and verify {@code chunk}. Completes with {@code true} when the bytes are in
     * the store, {@code false} when the request was cancelled, and exceptionally on a
     * hard failure.
     */
    public CompletableFuture<Boolean> fetch(ChunkEntry chunk) {
        Objects.requireNonNull(chunk, "chunk");
        try {
            if (store.hasVerified(chunk.sha256())) {
                return CompletableFuture.completedFuture(true);
            }
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        CompletableFuture<HttpResponse<byte[]>> send = http.sendAsync(rangeRequest(chunk),
                HttpResponse.BodyHandlers.ofByteArray());
        Flight flight = new Flight(send, outcome);
        Flight previous = inFlight.putIfAbsent(chunk.index(), flight);
        if (previous != null) {
            send.cancel(true);
            return previous.outcome;
        }
        send.whenComplete((response, error) -> {
            inFlight.remove(chunk.index(), flight);
            if (flight.aborted.get() || cancelled(send, error) || outcome.isDone()) {
                outcome.complete(false);
                return;
            }
            if (error != null) {
                outcome.completeExceptionally(error);
                return;
            }
            try {
                if (flight.aborted.get()) {
                    outcome.complete(false);
                    return;
                }
                commit(chunk, response);
                outcome.complete(true);
            } catch (Exception e) {
                outcome.completeExceptionally(e);
            }
        });
        return outcome;
    }

    public void cancel(int chunkIndex) {
        Flight flight = inFlight.remove(chunkIndex);
        if (flight == null) {
            return;
        }
        // Abort first so a response that lands on this thread cannot putVerified.
        // Complete before send.cancel so CancellationException is not a hard failure.
        flight.aborted.set(true);
        cancelled.incrementAndGet();
        flight.outcome.complete(false);
        flight.send.cancel(true);
    }

    public void cancelAll() {
        for (int chunkIndex : List.copyOf(inFlight.keySet())) {
            cancel(chunkIndex);
        }
    }

    private record Flight(CompletableFuture<HttpResponse<byte[]>> send, CompletableFuture<Boolean> outcome,
                          AtomicBoolean aborted) {
        Flight(CompletableFuture<HttpResponse<byte[]>> send, CompletableFuture<Boolean> outcome) {
            this(send, outcome, new AtomicBoolean());
        }
    }

    public long bytesReceived() {
        return bytesReceived.get();
    }

    public int cancelled() {
        return cancelled.get();
    }

    private static boolean cancelled(CompletableFuture<?> send, Throwable error) {
        if (send.isCancelled()) {
            return true;
        }
        while (error != null) {
            if (error instanceof CancellationException) {
                return true;
            }
            error = error.getCause();
        }
        return false;
    }

    private void commit(ChunkEntry chunk, HttpResponse<byte[]> response) throws IOException {
        int status = response.statusCode();
        if (status != 206 && status != 200) {
            throw new IOException("origin returned HTTP " + status + " for chunk " + chunk.index());
        }
        byte[] body = response.body();
        bytesReceived.addAndGet(body.length);
        if (body.length != chunk.length()) {
            throw new IOException("origin returned " + body.length + " bytes for chunk " + chunk.index()
                    + ", expected " + chunk.length());
        }
        store.putVerified(chunk.sha256(), body);
    }

    private HttpRequest rangeRequest(ChunkEntry chunk) {
        long last = chunk.offset() + chunk.length() - 1;
        return HttpRequest.newBuilder(fileUri())
                .GET()
                .timeout(settings.requestTimeout())
                .header("Range", "bytes=" + chunk.offset() + "-" + last)
                .build();
    }

    private URI fileUri() {
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String query = settings.runId().isBlank() ? "" : "?runId=" + encode(settings.runId());
        return URI.create(base + "/files/" + encode(fileName) + query);
    }

    private static String requireFileName(String fileName) {
        String name = Objects.requireNonNull(fileName, "fileName").trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("unsafe manifest fileName: " + fileName);
        }
        return name;
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
}
