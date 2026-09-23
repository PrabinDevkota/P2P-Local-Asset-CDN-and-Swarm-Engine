package com.prabin.swarmedge.peer.tracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Re-announces this peer on the tracker heartbeat so the 45 s field TTL stays fresh.
 * A failed announce is logged without the token and retried on the next tick.
 */
public final class AnnounceLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnnounceLoop.class);

    private final TrackerClient client;
    private final String bearerToken;
    private final Supplier<TrackerClient.Announce> body;
    private final Duration period;
    private final ScheduledExecutorService scheduler;

    public AnnounceLoop(TrackerClient client, String bearerToken, Supplier<TrackerClient.Announce> body,
                        Duration period) {
        this.client = Objects.requireNonNull(client, "client");
        this.bearerToken = Objects.requireNonNull(bearerToken, "bearerToken");
        this.body = Objects.requireNonNull(body, "body");
        this.period = Objects.requireNonNull(period, "period");
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be positive");
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tracker-announce");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        long millis = Math.max(1L, period.toMillis());
        scheduler.scheduleWithFixedDelay(this::tick, 0, millis, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            client.announce(bearerToken, body.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("tracker announce failed: {}", e.toString());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
