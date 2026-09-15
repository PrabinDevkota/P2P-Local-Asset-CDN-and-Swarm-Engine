package com.prabin.swarmedge.peer.laps;

/**
 * The five LAPS weights (blueprint P6-03, §8.2).
 *
 * <p>Weights are configuration, not constants in the scorer, because the paper has to
 * sensitivity-test them. A weight compiled into the code is not a variable, and a
 * scheduler study whose parameters cannot be varied is not a study.
 *
 * <p>{@link #defaults()} are the blueprint's suggested engineering values and are
 * explicitly <em>not</em> claimed optimal. Finding out what they should be is the
 * experiment; shipping them as the answer would be assuming the result.
 *
 * <p>They are required to sum to 1 so a peer score stays in 0..1 and two runs with
 * different weights remain comparable. Without that, raising one weight would also raise
 * every score, and a "better" configuration could be manufactured by scaling.
 *
 * @param locality   wL, how much being on the same switch is worth
 * @param throughput wT, how much observed goodput is worth
 * @param rtt        wR, how much latency is worth
 * @param capacity   wC, how much spare upload capacity is worth
 * @param health     wH, how much a record of working is worth
 */
public record LapsWeights(double locality, double throughput, double rtt, double capacity, double health) {

    private static final double SUM_TOLERANCE = 1e-9;

    public LapsWeights {
        requireShare(locality, "locality");
        requireShare(throughput, "throughput");
        requireShare(rtt, "rtt");
        requireShare(capacity, "capacity");
        requireShare(health, "health");
        double total = locality + throughput + rtt + capacity + health;
        if (Math.abs(total - 1.0) > SUM_TOLERANCE) {
            throw new IllegalArgumentException("weights must sum to 1 so scores stay comparable"
                    + " across configurations, got " + total);
        }
    }

    /** §8.2's suggested defaults: wL=0.35, wT=0.30, wR=0.15, wC=0.10, wH=0.10. */
    public static LapsWeights defaults() {
        return new LapsWeights(0.35, 0.30, 0.15, 0.10, 0.10);
    }

    /**
     * Locality and nothing else, which is baseline B2: the comparison that isolates what
     * the measured terms of LAPS are actually worth.
     */
    public static LapsWeights localityOnly() {
        return new LapsWeights(1.0, 0.0, 0.0, 0.0, 0.0);
    }

    private static void requireShare(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("weight " + name + " must be a finite number");
        }
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException("weight " + name + " must be in 0..1, got " + value);
        }
    }
}
