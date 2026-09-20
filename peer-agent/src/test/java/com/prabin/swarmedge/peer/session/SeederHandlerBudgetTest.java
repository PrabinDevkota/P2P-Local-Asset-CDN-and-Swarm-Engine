package com.prabin.swarmedge.peer.session;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P8-01: the seeder's upload ceiling is a token bucket. When it is empty the handler
 * answers BUSY, the same code as a full queue, rather than inventing a new refusal.
 */
class SeederHandlerBudgetTest {

    @Test
    void desktopDefaultsMatchWhatTheSeederUsedBeforeSettingsExisted() {
        SeederHandler.Settings desktop = SeederHandler.Settings.desktop();

        assertThat(desktop.maxQueuedRequests()).isEqualTo(32);
        assertThat(desktop.maxConcurrentSends()).isEqualTo(2);
        assertThat(desktop.uploadBytesPerSecond()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void edgeGetsADeeperQueueAndAFiniteRate() {
        SeederHandler.Settings edge = SeederHandler.Settings.edge(125_000_000);

        assertThat(edge.maxQueuedRequests()).isEqualTo(128);
        assertThat(edge.maxConcurrentSends()).isEqualTo(8);
        assertThat(edge.uploadBytesPerSecond()).isEqualTo(125_000_000);
    }

    @Test
    void anUnlimitedBudgetNeverRefuses() {
        SeederHandler.UploadBudget budget = new SeederHandler.UploadBudget(Long.MAX_VALUE);

        assertThat(budget.tryConsume(1)).isTrue();
        assertThat(budget.tryConsume(Integer.MAX_VALUE)).isTrue();
        assertThat(budget.tryConsume(1)).isTrue();
    }

    @Test
    void aFiniteBudgetRefusesOnceTheBurstIsSpent() {
        AtomicLong now = new AtomicLong(0);
        SeederHandler.UploadBudget budget = new SeederHandler.UploadBudget(1_000, now::get);

        assertThat(budget.tryConsume(600)).isTrue();
        assertThat(budget.tryConsume(400)).isTrue();
        assertThat(budget.tryConsume(1)).isFalse();
    }

    @Test
    void aRequestLargerThanTheBurstIsBusyRatherThanQueued() {
        SeederHandler.UploadBudget budget = new SeederHandler.UploadBudget(100);

        assertThat(budget.tryConsume(101)).isFalse();
    }

    @Test
    void tokensRefillFromTheClockSoALaterRequestCanProceed() {
        AtomicLong now = new AtomicLong(0);
        SeederHandler.UploadBudget budget = new SeederHandler.UploadBudget(1_000, now::get);

        assertThat(budget.tryConsume(1_000)).isTrue();
        assertThat(budget.tryConsume(1)).isFalse();

        now.set(1_000_000_000L);

        assertThat(budget.tryConsume(1_000)).isTrue();
    }

    @Test
    void aCancelledRequestReturnsItsTokens() {
        AtomicLong now = new AtomicLong(0);
        SeederHandler.UploadBudget budget = new SeederHandler.UploadBudget(1_000, now::get);

        assertThat(budget.tryConsume(800)).isTrue();
        budget.refund(800);

        assertThat(budget.tryConsume(1_000)).isTrue();
    }

    @Test
    void nonPositiveSettingsAreRejected() {
        assertThatThrownBy(() -> new SeederHandler.Settings(0, 2, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxQueuedRequests");
        assertThatThrownBy(() -> new SeederHandler.Settings(1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcurrentSends");
        assertThatThrownBy(() -> new SeederHandler.Settings(1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uploadBytesPerSecond");
    }
}
