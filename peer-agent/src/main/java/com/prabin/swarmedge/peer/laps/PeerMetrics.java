package com.prabin.swarmedge.peer.laps;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * What one peer has actually done for us (blueprint P6-02, §8.2, §8.3).
 *
 * <p>Every number here is measured. Goodput comes from a block that arrived and verified
 * its length, RTT from a PONG we matched to our own PING or from a block's turnaround,
 * and health from requests that succeeded against ones that timed out or broke the
 * protocol. Nothing a peer says about itself is recorded: a peer that advertises a fast
 * link is making a claim, and a score built from claims ranks liars first.
 *
 * <p>Both averages are exponentially weighted, so a peer that was fast an hour ago stops
 * mattering without any window bookkeeping. {@code alpha} is how much the newest sample
 * counts; the blueprint calls for a fixed baseline before adapting the window (§8.3), so
 * the smoothing is configuration and the default is not claimed optimal.
 *
 * <p>A fresh peer has no samples at all, and that is reported as empty rather than as
 * zero. Zero goodput is a statement about a peer that tried and failed; it must not be
 * how we describe one that has not been asked yet, or a new peer would rank below a
 * known-bad one and never get a chance to prove otherwise.
 *
 * <p>Updated from a session's event loop and read by a scorer on another thread, so
 * every method is synchronized.
 */
public final class PeerMetrics {

    /** Weight of the newest sample. Higher reacts faster and is noisier. */
    public static final double DEFAULT_ALPHA = 0.25;

    private final double alpha;

    private double goodputBytesPerSecond;
    private double rttMillis;
    private boolean hasGoodput;
    private boolean hasRtt;

    private long blocksCompleted;
    private long blocksFailed;
    private long bytesReceived;

    public PeerMetrics() {
        this(DEFAULT_ALPHA);
    }

    public PeerMetrics(double alpha) {
        if (!(alpha > 0) || alpha > 1) {
            throw new IllegalArgumentException("alpha must be in (0, 1], got " + alpha);
        }
        this.alpha = alpha;
    }

    /**
     * A block arrived whole. This is the only place goodput comes from.
     *
     * @param bytes    how many bytes landed, which is the length we asked for
     * @param elapsed  wall time from issuing the request to the last byte
     */
    public synchronized void blockCompleted(int bytes, Duration elapsed) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("a completed block carries bytes, got " + bytes);
        }
        Objects.requireNonNull(elapsed, "elapsed");
        blocksCompleted++;
        bytesReceived += bytes;

        long nanos = elapsed.toNanos();
        if (nanos <= 0) {
            // A block that reports no elapsed time is a clock artefact, not infinite
            // bandwidth. Count it as delivered and leave the average alone.
            return;
        }
        double sample = bytes * 1_000_000_000.0 / nanos;
        goodputBytesPerSecond = hasGoodput ? blend(goodputBytesPerSecond, sample) : sample;
        hasGoodput = true;
        // A delivered block is also an RTT sample, which is what keeps RTT alive on a
        // session that never has to send a PING.
        noteRtt(elapsed);
    }

    /** A request timed out, was refused, or the peer broke the protocol. */
    public synchronized void blockFailed() {
        blocksFailed++;
    }

    /** A PONG matched one of our PINGs. */
    public synchronized void pongObserved(Duration roundTrip) {
        Objects.requireNonNull(roundTrip, "roundTrip");
        if (roundTrip.isNegative()) {
            throw new IllegalArgumentException("a round trip cannot be negative: " + roundTrip);
        }
        noteRtt(roundTrip);
    }

    private void noteRtt(Duration sample) {
        double millis = sample.toNanos() / 1_000_000.0;
        rttMillis = hasRtt ? blend(rttMillis, millis) : millis;
        hasRtt = true;
    }

    private double blend(double current, double sample) {
        return alpha * sample + (1 - alpha) * current;
    }

    /** Empty until this peer has delivered something. */
    public synchronized OptionalDouble goodputBytesPerSecond() {
        return hasGoodput ? OptionalDouble.of(goodputBytesPerSecond) : OptionalDouble.empty();
    }

    /** Empty until a block or a PONG has come back. */
    public synchronized OptionalDouble rttMillis() {
        return hasRtt ? OptionalDouble.of(rttMillis) : OptionalDouble.empty();
    }

    /**
     * The §8.2 {@code healthScore}: the share of attempts that worked, in 0..1.
     *
     * <p>Empty with no attempts. A peer we have never asked is not healthy and not
     * unhealthy, and pretending to know is how an unproven peer gets shut out.
     */
    public synchronized OptionalDouble healthScore() {
        long attempts = blocksCompleted + blocksFailed;
        return attempts == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) blocksCompleted / attempts);
    }

    public synchronized long blocksCompleted() {
        return blocksCompleted;
    }

    public synchronized long blocksFailed() {
        return blocksFailed;
    }

    public synchronized long bytesReceived() {
        return bytesReceived;
    }

    /** True once there is anything measured to rank on. */
    public synchronized boolean hasSamples() {
        return hasGoodput || hasRtt || blocksFailed > 0;
    }

    @Override
    public synchronized String toString() {
        return "PeerMetrics[goodput=" + goodputBytesPerSecond() + "B/s, rtt=" + rttMillis()
                + "ms, ok=" + blocksCompleted + ", failed=" + blocksFailed + "]";
    }
}
