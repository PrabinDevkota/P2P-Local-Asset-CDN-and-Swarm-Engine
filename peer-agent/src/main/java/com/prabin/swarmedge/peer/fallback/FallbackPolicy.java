package com.prabin.swarmedge.peer.fallback;

import java.time.Duration;
import java.util.Objects;

/**
 * Progressive fallback timers (blueprint P8-02, §12.1).
 *
 * <p>t=0 is cache plus local peers. EDGE is offered after {@code edgeAfter}, origin
 * after {@code originAfter}, each shifted by a deterministic jitter so a flash crowd
 * does not hit origin on the same millisecond. The numbers are experiment inputs,
 * not universal constants, and they are not a 5-second all-or-nothing wait.
 *
 * @param edgeAfter            earliest EDGE dial, before jitter
 * @param originAfter          earliest origin GET, before jitter
 * @param jitter               uniform {@code ±} applied to each rung
 * @param maxOriginInFlight    how many origin chunk fetches may run at once
 * @param pipelineFillTarget   admit EDGE only when leased / capacity is below this
 */
public record FallbackPolicy(
        Duration edgeAfter,
        Duration originAfter,
        Duration jitter,
        int maxOriginInFlight,
        double pipelineFillTarget) {

    public enum Rung {
        EDGE,
        ORIGIN
    }

    public FallbackPolicy {
        Objects.requireNonNull(edgeAfter, "edgeAfter");
        Objects.requireNonNull(originAfter, "originAfter");
        Objects.requireNonNull(jitter, "jitter");
        if (edgeAfter.isNegative()) {
            throw new IllegalArgumentException("edgeAfter cannot be negative");
        }
        if (originAfter.isNegative() || originAfter.compareTo(edgeAfter) < 0) {
            throw new IllegalArgumentException("originAfter must be at least edgeAfter");
        }
        if (jitter.isNegative()) {
            throw new IllegalArgumentException("jitter cannot be negative");
        }
        if (maxOriginInFlight <= 0) {
            throw new IllegalArgumentException("maxOriginInFlight must be positive");
        }
        if (!Double.isFinite(pipelineFillTarget) || pipelineFillTarget < 0 || pipelineFillTarget > 1) {
            throw new IllegalArgumentException("pipelineFillTarget must be in 0..1");
        }
    }

    /** Blueprint §12.1 mid-range defaults: 500 ms EDGE, 2 s origin, 400 ms jitter. */
    public static FallbackPolicy defaults() {
        return new FallbackPolicy(Duration.ofMillis(500), Duration.ofMillis(2000),
                Duration.ofMillis(400), 2, 0.5);
    }

    /**
     * When this client may start a rung. Same seed and rung always give the same
     * delay, so a published run can be replayed.
     */
    public Duration delay(Rung rung, long clientSeed) {
        Objects.requireNonNull(rung, "rung");
        long base = (rung == Rung.EDGE ? edgeAfter : originAfter).toMillis();
        long spread = jitter.toMillis();
        if (spread == 0L) {
            return Duration.ofMillis(base);
        }
        long mixed = mix(clientSeed, rung.ordinal());
        long span = 2 * spread + 1;
        long offset = Math.floorMod(mixed, span) - spread;
        return Duration.ofMillis(Math.max(0L, base + offset));
    }

    /** SplitMix64 so nearby seeds do not produce nearby offsets. */
    static long mix(long seed, int rung) {
        long z = seed + 0x9E3779B97F4A7C15L + (rung * 0xBF58476D1CE4E5B9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
