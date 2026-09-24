package com.prabin.swarmedge.benchmark;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TcNetemTest {

    @Test
    void removeRunsAfterApplyEvenWhenTheBodyFails() throws Exception {
        List<List<String>> calls = new ArrayList<>();
        TcNetem netem = new TcNetem(new NetworkImpairment("rtt-10", 10, 1, 1), "eth0", args -> {
            calls.add(args);
            return 0;
        });
        netem.apply();
        try {
            failTheRun();
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("run failed");
        } finally {
            netem.close();
        }
        assertThat(netem.applied()).isFalse();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).contains("netem", "delay", "10ms");
        assertThat(calls.get(1)).contains("del");
    }

    @Test
    void aMissingTcCommandDoesNotLeaveTheSessionApplied() throws Exception {
        TcNetem netem = new TcNetem(new NetworkImpairment("rtt-50", 50, 0, 0), "eth0", args -> {
            throw new java.io.IOException("cannot find tc");
        });
        netem.apply();
        netem.remove();
        assertThat(netem.applied()).isFalse();
        assertThat(netem.note()).contains("tc not available");
    }

    private static void failTheRun() {
        throw new IllegalStateException("run failed");
    }
}
