package com.prabin.swarmedge.peer.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineWindowTest {

    @Test
    void theWindowStaysAtEightUntilTheBaselineIsStable() {
        PipelineWindow window = new PipelineWindow();

        for (int i = 0; i < PipelineWindow.STABLE_AFTER - 1; i++) {
            window.onBlockCompleted();
        }

        assertThat(window.stable()).isFalse();
        assertThat(window.limit()).isEqualTo(PipelineWindow.INITIAL);
    }

    @Test
    void aStableWindowGrowsByOnePerFurtherBaseline() {
        PipelineWindow window = new PipelineWindow();
        for (int i = 0; i < PipelineWindow.STABLE_AFTER; i++) {
            window.onBlockCompleted();
        }
        assertThat(window.stable()).isTrue();
        assertThat(window.limit()).isEqualTo(PipelineWindow.INITIAL);

        for (int i = 0; i < PipelineWindow.STABLE_AFTER; i++) {
            window.onBlockCompleted();
        }

        assertThat(window.limit()).isEqualTo(PipelineWindow.INITIAL + 1);
    }

    @Test
    void aFailureHalvesTheWindowAndClearsStability() {
        PipelineWindow window = new PipelineWindow();
        for (int i = 0; i < PipelineWindow.STABLE_AFTER; i++) {
            window.onBlockCompleted();
        }

        window.onBlockFailed();

        assertThat(window.stable()).isFalse();
        assertThat(window.limit()).isEqualTo(4);
    }

    @Test
    void theWindowDoesNotShrinkBelowTheFloor() {
        PipelineWindow window = new PipelineWindow();
        window.onBlockFailed();
        window.onBlockFailed();
        window.onBlockFailed();

        assertThat(window.limit()).isEqualTo(PipelineWindow.FLOOR);
    }
}
