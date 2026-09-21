package com.prabin.swarmedge.peer.laps;

import com.prabin.swarmedge.common.id.PeerId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PeerQuarantineTest {

    private static final Instant T0 = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void hashMismatchesBelowTheThresholdDoNotQuarantine() {
        MutableClock clock = new MutableClock(T0);
        PeerQuarantine quarantine = new PeerQuarantine(clock, new PeerQuarantine.Settings(3, Duration.ofSeconds(60)));
        PeerId peer = peerId(1);

        quarantine.noteHashMismatch(peer);
        quarantine.noteHashMismatch(peer);

        assertThat(quarantine.isQuarantined(peer)).isFalse();
        assertThat(quarantine.strikes(peer)).isEqualTo(2);
    }

    @Test
    void theThresholdStrikeStartsACooldown() {
        MutableClock clock = new MutableClock(T0);
        PeerQuarantine quarantine = new PeerQuarantine(clock, new PeerQuarantine.Settings(3, Duration.ofSeconds(60)));
        PeerId peer = peerId(1);

        quarantine.noteHashMismatch(peer);
        quarantine.noteHashMismatch(peer);
        quarantine.noteProtocolViolation(peer);

        assertThat(quarantine.isQuarantined(peer)).isTrue();
        clock.set(T0.plus(Duration.ofSeconds(59)));
        assertThat(quarantine.isQuarantined(peer)).isTrue();
    }

    @Test
    void aPeerIsEligibleAgainWhenTheCooldownEnds() {
        MutableClock clock = new MutableClock(T0);
        PeerQuarantine quarantine = new PeerQuarantine(clock, new PeerQuarantine.Settings(3, Duration.ofSeconds(60)));
        PeerId peer = peerId(1);
        quarantine.noteHashMismatch(peer);
        quarantine.noteHashMismatch(peer);
        quarantine.noteHashMismatch(peer);

        clock.set(T0.plus(Duration.ofSeconds(60)));

        assertThat(quarantine.isQuarantined(peer)).isFalse();
        assertThat(quarantine.strikes(peer)).isZero();
    }

    @Test
    void peersAreTrackedIndependently() {
        MutableClock clock = new MutableClock(T0);
        PeerQuarantine quarantine = new PeerQuarantine(clock, new PeerQuarantine.Settings(1, Duration.ofSeconds(30)));

        quarantine.noteHashMismatch(peerId(1));

        assertThat(quarantine.isQuarantined(peerId(1))).isTrue();
        assertThat(quarantine.isQuarantined(peerId(2))).isFalse();
    }

    @Test
    void noneNeverQuarantines() {
        PeerQuarantine none = PeerQuarantine.none();
        none.noteHashMismatch(peerId(1));
        none.noteProtocolViolation(peerId(1));

        assertThat(none.isQuarantined(peerId(1))).isFalse();
    }

    private static PeerId peerId(int id) {
        byte[] bits = new byte[16];
        Arrays.fill(bits, (byte) id);
        return PeerId.of(bits);
    }

    /** A clock tests can step without depending on the wall. */
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
