package com.prabin.swarmedge.benchmark;

/**
 * Scenario dispatcher. Only baseline B0 exists so far; see {@link B0Runner}.
 *
 * <p>Phase 10 turns this into the real harness: netem and fault injection,
 * run IDs and seeds, and immutable raw run folders under {@code research/raw/}.
 */
public final class BenchmarkRunner {

    private BenchmarkRunner() {
    }
}
