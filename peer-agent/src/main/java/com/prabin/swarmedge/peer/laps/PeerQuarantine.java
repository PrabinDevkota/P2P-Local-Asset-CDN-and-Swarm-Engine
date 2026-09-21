package com.prabin.swarmedge.peer.laps;

import com.prabin.swarmedge.common.id.PeerId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary ineligibility after hash or protocol failures (blueprint P9-02).
 *
 * <p>A quarantined peer is not dialled. It is never a reason to skip SHA-256 on a
 * chunk that does arrive: reputation ranks sources, it does not authorize bytes.
 * Strikes accumulate; crossing the threshold starts a cooldown, after which the
 * peer is eligible again with a clean record.
 */
public final class PeerQuarantine {

    public static final Settings DEFAULTS = new Settings(3, Duration.ofSeconds(60));

    private static final PeerQuarantine NONE = new PeerQuarantine(Clock.systemUTC(), DEFAULTS, true);

    public static PeerQuarantine none() {
        return NONE;
    }

    private final Clock clock;
    private final Settings settings;
    private final boolean disabled;
    private final ConcurrentHashMap<PeerId, State> states = new ConcurrentHashMap<>();

    public PeerQuarantine(Clock clock, Settings settings) {
        this(clock, settings, false);
    }

    public PeerQuarantine() {
        this(Clock.systemUTC(), DEFAULTS, false);
    }

    private PeerQuarantine(Clock clock, Settings settings, boolean disabled) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.disabled = disabled;
    }

    public void noteHashMismatch(PeerId peerId) {
        if (disabled) {
            return;
        }
        strike(peerId);
    }

    public void noteProtocolViolation(PeerId peerId) {
        if (disabled) {
            return;
        }
        strike(peerId);
    }

    public boolean isQuarantined(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId");
        if (disabled) {
            return false;
        }
        State state = states.get(peerId);
        if (state == null) {
            return false;
        }
        Instant now = clock.instant();
        if (state.quarantinedUntil.isAfter(now)) {
            return true;
        }
        if (state.strikes >= settings.strikeThreshold() && !state.quarantinedUntil.equals(Instant.EPOCH)) {
            states.remove(peerId);
        }
        return false;
    }

    public int strikes(PeerId peerId) {
        State state = states.get(Objects.requireNonNull(peerId, "peerId"));
        return state == null ? 0 : state.strikes;
    }

    private void strike(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId");
        states.compute(peerId, (id, previous) -> {
            int next = (previous == null ? 0 : previous.strikes) + 1;
            Instant until = next >= settings.strikeThreshold()
                    ? clock.instant().plus(settings.cooldown())
                    : Instant.EPOCH;
            return new State(next, until);
        });
    }

    public record Settings(int strikeThreshold, Duration cooldown) {
        public Settings {
            if (strikeThreshold < 1) {
                throw new IllegalArgumentException("strikeThreshold must be at least 1");
            }
            Objects.requireNonNull(cooldown, "cooldown");
            if (cooldown.isNegative()) {
                throw new IllegalArgumentException("cooldown must be non-negative");
            }
        }
    }

    private record State(int strikes, Instant quarantinedUntil) {
    }
}
