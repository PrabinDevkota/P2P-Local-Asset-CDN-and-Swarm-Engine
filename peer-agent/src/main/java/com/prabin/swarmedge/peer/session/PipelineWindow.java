package com.prabin.swarmedge.peer.session;

/**
 * Outstanding-request window (blueprint §8.3).
 *
 * <p>Starts at the fixed baseline of 8. It does not move until that many clean
 * block completions have been seen, and then it grows by one only every further
 * baseline of clean completions. A failure halves it, down to 2, and the window
 * has to earn stability again. Callers that want the paper baselines leave this
 * unset and stay at 8 for the whole transfer.
 */
public final class PipelineWindow {

    public static final int INITIAL = 8;
    public static final int FLOOR = 2;
    public static final int CEILING = 32;
    public static final int STABLE_AFTER = 16;

    private int limit = INITIAL;
    private int clean;
    private boolean stable;

    public int limit() {
        return limit;
    }

    public boolean stable() {
        return stable;
    }

    public int onBlockCompleted() {
        clean++;
        if (!stable && clean >= STABLE_AFTER) {
            stable = true;
            clean = 0;
        }
        if (stable && clean >= STABLE_AFTER && limit < CEILING) {
            limit++;
            clean = 0;
        }
        return limit;
    }

    public int onBlockFailed() {
        clean = 0;
        stable = false;
        limit = Math.max(FLOOR, limit / 2);
        return limit;
    }
}
