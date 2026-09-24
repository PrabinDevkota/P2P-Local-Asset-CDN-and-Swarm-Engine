package com.prabin.swarmedge.benchmark;

/**
 * A named network profile (blueprint §18.4). Zero delay and zero loss is loopback:
 * the runner still records the profile, and it does not invent a WAN result from it.
 */
public record NetworkImpairment(String profile, int delayMillis, int jitterMillis, double lossPercent) {

    public NetworkImpairment {
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("network.profile is required");
        }
        if (delayMillis < 0 || jitterMillis < 0) {
            throw new IllegalArgumentException("delay and jitter cannot be negative");
        }
        if (lossPercent < 0 || lossPercent > 100) {
            throw new IllegalArgumentException("lossPercent must be between 0 and 100");
        }
    }

    public boolean isLoopback() {
        return delayMillis == 0 && jitterMillis == 0 && lossPercent == 0;
    }
}
