package com.prabin.tracker.rate;

import com.prabin.swarmedge.common.id.PeerId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AnnounceRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void aPeerMayAnnounceUpToTheCapInsideTheWindow() {
        MutableClock clock = new MutableClock(T0);
        AnnounceRateLimiter limiter = new AnnounceRateLimiter.Memory(3, Duration.ofSeconds(1), clock);
        PeerId peer = peerId(1);

        assertThat(limiter.tryAcquire(peer)).isTrue();
        assertThat(limiter.tryAcquire(peer)).isTrue();
        assertThat(limiter.tryAcquire(peer)).isTrue();
        assertThat(limiter.tryAcquire(peer)).isFalse();
    }

    @Test
    void anotherPeerIsNotChargedForSomeoneElsesBurst() {
        MutableClock clock = new MutableClock(T0);
        AnnounceRateLimiter limiter = new AnnounceRateLimiter.Memory(1, Duration.ofSeconds(1), clock);

        assertThat(limiter.tryAcquire(peerId(1))).isTrue();
        assertThat(limiter.tryAcquire(peerId(1))).isFalse();
        assertThat(limiter.tryAcquire(peerId(2))).isTrue();
    }

    @Test
    void theWindowResetsAfterItElapses() {
        MutableClock clock = new MutableClock(T0);
        AnnounceRateLimiter limiter = new AnnounceRateLimiter.Memory(1, Duration.ofSeconds(1), clock);
        PeerId peer = peerId(1);
        assertThat(limiter.tryAcquire(peer)).isTrue();
        assertThat(limiter.tryAcquire(peer)).isFalse();

        clock.set(T0.plusSeconds(1));

        assertThat(limiter.tryAcquire(peer)).isTrue();
    }

    @Test
    void redisKeyMatchesTheBlueprintLayout() {
        assertThat(AnnounceRateLimiter.redisKey(peerId(1)))
                .isEqualTo("rate:" + peerId(1).toHex() + ":announce");
    }

    private static PeerId peerId(int id) {
        byte[] bits = new byte[16];
        Arrays.fill(bits, (byte) id);
        return PeerId.of(bits);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
