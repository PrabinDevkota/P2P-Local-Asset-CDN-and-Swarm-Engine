package com.prabin.tracker.rate;

import com.prabin.swarmedge.common.id.PeerId;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps how often one peer may announce (blueprint §10.2, {@code rate:{peerId}:announce}).
 *
 * <p>A burst is refused with a rate-limit error. Other peers are unaffected. The
 * limit is an abuse control, not a content-trust decision.
 */
public interface AnnounceRateLimiter {

    int DEFAULT_MAX_PER_WINDOW = 8;
    Duration DEFAULT_WINDOW = Duration.ofSeconds(1);

    boolean tryAcquire(PeerId peerId);

    static String redisKey(PeerId peerId) {
        return "rate:" + Objects.requireNonNull(peerId, "peerId").toHex() + ":announce";
    }

    final class Memory implements AnnounceRateLimiter {
        private final int maxPerWindow;
        private final Duration window;
        private final Clock clock;
        private final ConcurrentHashMap<PeerId, Window> windows = new ConcurrentHashMap<>();

        public Memory(int maxPerWindow, Duration window, Clock clock) {
            if (maxPerWindow < 1) {
                throw new IllegalArgumentException("maxPerWindow must be at least 1");
            }
            this.maxPerWindow = maxPerWindow;
            this.window = Objects.requireNonNull(window, "window");
            if (window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("window must be positive");
            }
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        public Memory(int maxPerWindow, Duration window) {
            this(maxPerWindow, window, Clock.systemUTC());
        }

        @Override
        public boolean tryAcquire(PeerId peerId) {
            Objects.requireNonNull(peerId, "peerId");
            long now = clock.millis();
            Window current = windows.compute(peerId, (id, previous) -> {
                if (previous == null || now - previous.startedAtMillis >= window.toMillis()) {
                    return new Window(now, 1);
                }
                return new Window(previous.startedAtMillis, previous.count + 1);
            });
            return current.count <= maxPerWindow;
        }

        private record Window(long startedAtMillis, int count) {
        }
    }

    final class Redis implements AnnounceRateLimiter {
        private final StringRedisTemplate redis;
        private final int maxPerWindow;
        private final Duration window;

        public Redis(StringRedisTemplate redis, int maxPerWindow, Duration window) {
            this.redis = Objects.requireNonNull(redis, "redis");
            if (maxPerWindow < 1) {
                throw new IllegalArgumentException("maxPerWindow must be at least 1");
            }
            this.maxPerWindow = maxPerWindow;
            this.window = Objects.requireNonNull(window, "window");
            if (window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("window must be positive");
            }
        }

        @Override
        public boolean tryAcquire(PeerId peerId) {
            Objects.requireNonNull(peerId, "peerId");
            String key = redisKey(peerId);
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return false;
            }
            if (count == 1L) {
                redis.expire(key, window);
            }
            return count <= maxPerWindow;
        }
    }

    final class LimitedException extends RuntimeException {
        public LimitedException(PeerId peerId) {
            super("announce rate limit exceeded for peer " + peerId.toHex());
        }
    }
}
