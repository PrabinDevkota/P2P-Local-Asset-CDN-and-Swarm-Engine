package com.prabin.swarmedge.origin;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Origin byte accounting for baseline B0. Counts bytes that actually reached the
 * socket, per run and per asset, so an aborted transfer is not billed as delivered.
 *
 * <p>This is measurement, not policy: the ledger never decides what is served.
 * Benchmarks read it; nothing in the transfer path branches on it.
 *
 * <p>P8-04 also keeps a one-second peak: the busiest wall-clock second of bytes
 * that reached the socket, per run and per asset. A flash-crowd experiment needs
 * that spike, not only the total.
 */
public final class OriginByteLedger {

    /** Used when a request carries no {@code runId}, so ad-hoc traffic is still counted. */
    public static final String UNATTRIBUTED_RUN = "unattributed";

    private static final long WINDOW_MILLIS = 1_000L;

    private final Map<Key, Counters> counters = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public OriginByteLedger() {
        this(System::currentTimeMillis);
    }

    /** Visible for tests that need a frozen or stepped clock. */
    public OriginByteLedger(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void recordServed(String runId, String assetName, long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be non-negative");
        }
        Counters entry = counters.computeIfAbsent(key(runId, assetName), unused -> new Counters());
        entry.bytes.addAndGet(bytes);
        entry.requests.incrementAndGet();
        long bucket = clock.getAsLong() / WINDOW_MILLIS;
        long inWindow = entry.window.computeIfAbsent(bucket, unused -> new AtomicLong()).addAndGet(bytes);
        entry.peak.accumulateAndGet(inWindow, Math::max);
        long keep = bucket - 1;
        entry.window.keySet().removeIf(existing -> existing < keep);
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

    /** Busiest one-second window for this run and asset. */
    public long peakBytes(String runId, String assetName) {
        Counters entry = counters.get(key(runId, assetName));
        return entry == null ? 0L : entry.peak.get();
    }

    /** Busiest one-second window across every asset of this run. */
    public long peakBytesForRun(String runId) {
        String run = normalizeRun(runId);
        return counters.entrySet().stream()
                .filter(entry -> entry.getKey().runId().equals(run))
                .mapToLong(entry -> entry.getValue().peak.get())
                .max()
                .orElse(0L);
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
        private final AtomicLong peak = new AtomicLong();
        private final ConcurrentHashMap<Long, AtomicLong> window = new ConcurrentHashMap<>();
    }
}
