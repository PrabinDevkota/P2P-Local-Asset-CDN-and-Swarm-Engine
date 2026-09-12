package com.prabin.swarmedge.origin;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Origin byte accounting for baseline B0. Counts bytes that actually reached the
 * socket, per run and per asset, so an aborted transfer is not billed as delivered.
 *
 * <p>This is measurement, not policy: the ledger never decides what is served.
 * Benchmarks read it; nothing in the transfer path branches on it.
 */
public final class OriginByteLedger {

    /** Used when a request carries no {@code runId}, so ad-hoc traffic is still counted. */
    public static final String UNATTRIBUTED_RUN = "unattributed";

    private final Map<Key, Counters> counters = new ConcurrentHashMap<>();

    public void recordServed(String runId, String assetName, long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be non-negative");
        }
        Counters entry = counters.computeIfAbsent(key(runId, assetName), unused -> new Counters());
        entry.bytes.addAndGet(bytes);
        entry.requests.incrementAndGet();
    }

    public long bytes(String runId, String assetName) {
        Counters entry = counters.get(key(runId, assetName));
        return entry == null ? 0L : entry.bytes.get();
    }

    public long requests(String runId, String assetName) {
        Counters entry = counters.get(key(runId, assetName));
        return entry == null ? 0L : entry.requests.get();
    }

    public long bytesForRun(String runId) {
        String run = normalizeRun(runId);
        return counters.entrySet().stream()
                .filter(entry -> entry.getKey().runId().equals(run))
                .mapToLong(entry -> entry.getValue().bytes.get())
                .sum();
    }

    public long totalBytes() {
        return counters.values().stream().mapToLong(entry -> entry.bytes.get()).sum();
    }

    /** Stable order so a written run record does not churn between runs. */
    public List<Line> lines() {
        return counters.entrySet().stream()
                .map(entry -> new Line(
                        entry.getKey().runId(),
                        entry.getKey().assetName(),
                        entry.getValue().bytes.get(),
                        entry.getValue().requests.get()))
                .sorted(Comparator.comparing(Line::runId).thenComparing(Line::assetName))
                .toList();
    }

    public void reset() {
        counters.clear();
    }

    static String normalizeRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return UNATTRIBUTED_RUN;
        }
        return runId.trim();
    }

    private static Key key(String runId, String assetName) {
        return new Key(normalizeRun(runId), Objects.requireNonNull(assetName, "assetName"));
    }

    public record Line(String runId, String assetName, long bytes, long requests) {
    }

    private record Key(String runId, String assetName) {
    }

    private static final class Counters {
        private final AtomicLong bytes = new AtomicLong();
        private final AtomicLong requests = new AtomicLong();
    }
}
