package com.prabin.swarmedge.benchmark;

/**
 * Something a run turns on before the transfer and must turn off afterwards
 * (blueprint P10-02). {@link #close()} removes the impairment even when the run failed.
 */
public interface ImpairmentSession extends AutoCloseable {

    void apply();

    void remove();

    boolean applied();

    @Override
    default void close() {
        remove();
    }

    /** No tc/netem. Used when the scenario names a loopback profile. */
    static ImpairmentSession none() {
        return new ImpairmentSession() {
            private boolean open;

            @Override
            public void apply() {
                open = true;
            }

            @Override
            public void remove() {
                open = false;
            }

            @Override
            public boolean applied() {
                return open;
            }
        };
    }
}
