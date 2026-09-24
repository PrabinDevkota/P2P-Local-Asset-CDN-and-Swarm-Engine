package com.prabin.swarmedge.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Starts {@code scale.clients} pieces of work and waits until every one finishes
 * (blueprint §18.4 flash-crowd counts). The work itself is the caller's.
 */
public final class ClientFanout {

    private ClientFanout() {
    }

    public static <T> List<T> run(int clients, Callable<T> work) throws Exception {
        if (clients <= 0) {
            throw new IllegalArgumentException("clients must be positive");
        }
        Objects.requireNonNull(work, "work");
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(clients, 8));
        try {
            List<Future<T>> futures = new ArrayList<>(clients);
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(work));
            }
            List<T> results = new ArrayList<>(clients);
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
