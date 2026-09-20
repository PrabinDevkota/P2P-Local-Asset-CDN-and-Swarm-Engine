package com.prabin.swarmedge.peer.fallback;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P8-02: 100 clients must not start origin on the exact same timestamp.
 */
class FallbackPolicyTest {

    @Test
    void theSameSeedReplaysTheSameDelay() {
        FallbackPolicy policy = FallbackPolicy.defaults();

        Duration first = policy.delay(FallbackPolicy.Rung.ORIGIN, 20260920L);
        Duration again = policy.delay(FallbackPolicy.Rung.ORIGIN, 20260920L);

        assertThat(again).isEqualTo(first);
        assertThat(first.toMillis()).isBetween(1600L, 2400L);
    }

    @Test
    void oneHundredClientsDoNotShareOneOriginStart() {
        FallbackPolicy policy = FallbackPolicy.defaults();
        Set<Long> starts = new HashSet<>();
        for (int client = 0; client < 100; client++) {
            starts.add(policy.delay(FallbackPolicy.Rung.ORIGIN, client).toMillis());
        }

        assertThat(starts.size()).isGreaterThan(1);
        assertThat(starts).allSatisfy(millis -> assertThat(millis).isBetween(1600L, 2400L));
    }

    @Test
    void edgeComesBeforeOriginForTheSameClient() {
        FallbackPolicy policy = FallbackPolicy.defaults();

        for (int client = 0; client < 20; client++) {
            Duration edge = policy.delay(FallbackPolicy.Rung.EDGE, client);
            Duration origin = policy.delay(FallbackPolicy.Rung.ORIGIN, client);
            assertThat(edge).isLessThan(origin);
        }
    }

    @Test
    void zeroJitterIsExactlyTheConfiguredDelay() {
        FallbackPolicy policy = new FallbackPolicy(Duration.ofMillis(300), Duration.ofMillis(1500),
                Duration.ZERO, 2, 0.5);

        assertThat(policy.delay(FallbackPolicy.Rung.EDGE, 1)).isEqualTo(Duration.ofMillis(300));
        assertThat(policy.delay(FallbackPolicy.Rung.ORIGIN, 99)).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void originCannotBeScheduledBeforeEdge() {
        assertThatThrownBy(() -> new FallbackPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1),
                Duration.ZERO, 2, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originAfter");
    }
}
