package com.prabin.swarmedge.peer.laps;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-02 acceptance: per-peer state moves only on observed transfer or ping data.
 */
class PeerMetricsTest {

    private static final int BLOCK = 256 * 1024;

    @Test
    void aFreshPeerReportsNothingRatherThanZero() {
        PeerMetrics metrics = new PeerMetrics();

        // Zero goodput describes a peer that tried and failed. A peer nobody has asked
        // yet must not be described that way, or it ranks below a known-bad one and
        // never gets the chance to prove otherwise.
        assertThat(metrics.goodputBytesPerSecond()).isEmpty();
        assertThat(metrics.rttMillis()).isEmpty();
        assertThat(metrics.healthScore()).isEmpty();
        assertThat(metrics.hasSamples()).isFalse();
    }

    @Test
    void oneDeliveredBlockIsEnoughToMeasureGoodputAndRtt() {
        PeerMetrics metrics = new PeerMetrics();

        metrics.blockCompleted(BLOCK, Duration.ofMillis(100));

        // 256 KiB in 0.1 s.
        assertThat(metrics.goodputBytesPerSecond()).hasValue(BLOCK * 10.0);
        assertThat(metrics.rttMillis()).hasValue(100.0);
        assertThat(metrics.blocksCompleted()).isEqualTo(1);
        assertThat(metrics.bytesReceived()).isEqualTo(BLOCK);
    }

    @Test
    void theAverageLeansOnRecentSamplesWithoutJumpingToThem() {
        PeerMetrics metrics = new PeerMetrics(0.25);
        metrics.blockCompleted(BLOCK, Duration.ofMillis(100));
        double first = metrics.goodputBytesPerSecond().orElseThrow();

        // Ten times slower. The average must move towards it, not to it.
        metrics.blockCompleted(BLOCK, Duration.ofSeconds(1));
        double second = metrics.goodputBytesPerSecond().orElseThrow();

        assertThat(second).isLessThan(first);
        assertThat(second).isGreaterThan(BLOCK * 1.0);
        assertThat(second).isEqualTo(0.25 * BLOCK + 0.75 * first);
    }

    @Test
    void aPeerThatKeepsBeingSlowIsEventuallyDescribedAsSlow() {
        PeerMetrics metrics = new PeerMetrics(0.25);
        metrics.blockCompleted(BLOCK, Duration.ofMillis(10));

        for (int i = 0; i < 40; i++) {
            metrics.blockCompleted(BLOCK, Duration.ofSeconds(1));
        }

        // An old fast sample stops mattering, with no window bookkeeping.
        assertThat(metrics.goodputBytesPerSecond().orElseThrow())
                .isCloseTo(BLOCK, org.assertj.core.data.Percentage.withPercentage(1));
        assertThat(metrics.rttMillis().orElseThrow())
                .isCloseTo(1000.0, org.assertj.core.data.Percentage.withPercentage(1));
    }

    @Test
    void aPongIsAnRttSampleAndNothingElse() {
        PeerMetrics metrics = new PeerMetrics();

        metrics.pongObserved(Duration.ofMillis(8));

        assertThat(metrics.rttMillis()).hasValue(8.0);
        // A peer answering pings has proved nothing about throughput.
        assertThat(metrics.goodputBytesPerSecond()).isEmpty();
        assertThat(metrics.healthScore()).isEmpty();
    }

    @Test
    void healthIsTheShareOfAttemptsThatWorked() {
        PeerMetrics metrics = new PeerMetrics();

        metrics.blockCompleted(BLOCK, Duration.ofMillis(50));
        metrics.blockCompleted(BLOCK, Duration.ofMillis(50));
        metrics.blockCompleted(BLOCK, Duration.ofMillis(50));
        metrics.blockFailed();

        assertThat(metrics.healthScore()).hasValue(0.75);
        assertThat(metrics.blocksFailed()).isEqualTo(1);
    }

    @Test
    void aPeerThatOnlyFailsIsMeasurablyUnhealthyWithoutAnyGoodput() {
        PeerMetrics metrics = new PeerMetrics();

        metrics.blockFailed();
        metrics.blockFailed();

        assertThat(metrics.healthScore()).hasValue(0.0);
        assertThat(metrics.goodputBytesPerSecond()).isEmpty();
        // It has been asked and it answered badly, which is a sample.
        assertThat(metrics.hasSamples()).isTrue();
    }

    @Test
    void aBlockThatReportsNoElapsedTimeIsNotInfiniteBandwidth() {
        PeerMetrics metrics = new PeerMetrics();
        metrics.blockCompleted(BLOCK, Duration.ofMillis(100));
        double before = metrics.goodputBytesPerSecond().orElseThrow();

        // A coarse clock can round a fast local block to zero. Counting it as delivered
        // is right; letting it claim unbounded throughput is not.
        metrics.blockCompleted(BLOCK, Duration.ZERO);

        assertThat(metrics.goodputBytesPerSecond()).hasValue(before);
        assertThat(metrics.blocksCompleted()).isEqualTo(2);
        assertThat(metrics.bytesReceived()).isEqualTo(2L * BLOCK);
    }

    @Test
    void nothingCanBeRecordedWithoutBytesOrWithNegativeTime() {
        PeerMetrics metrics = new PeerMetrics();

        assertThatThrownBy(() -> metrics.blockCompleted(0, Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries bytes");
        assertThatThrownBy(() -> metrics.pongObserved(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void anAlphaOutsideItsRangeIsRejected() {
        assertThatThrownBy(() -> new PeerMetrics(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alpha");
        assertThatThrownBy(() -> new PeerMetrics(1.5))
                .isInstanceOf(IllegalArgumentException.class);
        // 1.0 is legal and means "only the newest sample counts".
        PeerMetrics jumpy = new PeerMetrics(1.0);
        jumpy.blockCompleted(BLOCK, Duration.ofMillis(100));
        jumpy.blockCompleted(BLOCK, Duration.ofSeconds(1));
        assertThat(jumpy.goodputBytesPerSecond()).hasValue(BLOCK * 1.0);
    }
}
