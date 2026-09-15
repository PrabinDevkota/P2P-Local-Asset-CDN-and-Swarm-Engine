package com.prabin.swarmedge.benchmark;

/**
 * Scenario dispatcher. Baselines B0 (origin-only, {@link B0Runner}) and B1 (swarm,
 * {@link SwarmRunner}) exist so far.
 *
 * <p>Phase 10 turns this into the real harness: netem and fault injection,
 * run IDs and seeds, and immutable raw run folders under {@code research/raw/}.
 */
public final class BenchmarkRunner {

    private BenchmarkRunner() {
    }
}
