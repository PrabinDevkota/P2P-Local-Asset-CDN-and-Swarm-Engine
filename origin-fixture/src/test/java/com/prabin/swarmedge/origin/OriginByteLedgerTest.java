package com.prabin.swarmedge.origin;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class OriginByteLedgerTest {

    @Test
    void keepsRunsAndAssetsApart() {
        OriginByteLedger ledger = new OriginByteLedger();

        ledger.recordServed("run-1", "game-x.bin", 100);
        ledger.recordServed("run-1", "game-x.bin", 50);
        ledger.recordServed("run-1", "game-y.bin", 7);
        ledger.recordServed("run-2", "game-x.bin", 1);

        assertThat(ledger.bytes("run-1", "game-x.bin")).isEqualTo(150);
        assertThat(ledger.requests("run-1", "game-x.bin")).isEqualTo(2);
        assertThat(ledger.bytesForRun("run-1")).isEqualTo(157);
        assertThat(ledger.bytesForRun("run-2")).isEqualTo(1);
        assertThat(ledger.totalBytes()).isEqualTo(158);
    }

    @Test
    void unknownRunOrAssetReadsAsZeroRatherThanFailing() {
        OriginByteLedger ledger = new OriginByteLedger();

        assertThat(ledger.bytes("run-1", "missing.bin")).isZero();
        assertThat(ledger.requests("run-1", "missing.bin")).isZero();
        assertThat(ledger.bytesForRun("nobody")).isZero();
        assertThat(ledger.lines()).isEmpty();
    }

    @Test
    void trafficWithoutARunIdIsStillCounted() {
        OriginByteLedger ledger = new OriginByteLedger();

        ledger.recordServed(null, "game-x.bin", 4);
        ledger.recordServed("  ", "game-x.bin", 6);

        assertThat(ledger.bytes(OriginByteLedger.UNATTRIBUTED_RUN, "game-x.bin")).isEqualTo(10);
    }

    @Test
    void linesAreSortedSoRunRecordsAreStable() {
        OriginByteLedger ledger = new OriginByteLedger();

        ledger.recordServed("run-2", "b.bin", 2);
        ledger.recordServed("run-1", "b.bin", 1);
        ledger.recordServed("run-1", "a.bin", 3);

        assertThat(ledger.lines())
                .extracting(OriginByteLedger.Line::runId, OriginByteLedger.Line::assetName,
                        OriginByteLedger.Line::bytes)
                .containsExactly(
                        tuple("run-1", "a.bin", 3L),
                        tuple("run-1", "b.bin", 1L),
                        tuple("run-2", "b.bin", 2L));
    }

    @Test
    void rejectsNegativeBytes() {
        OriginByteLedger ledger = new OriginByteLedger();

        assertThatThrownBy(() -> ledger.recordServed("run-1", "a.bin", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentRequestsLoseNoBytes() throws Exception {
        OriginByteLedger ledger = new OriginByteLedger();
        int threads = 8;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    awaitQuietly(start);
                    for (int n = 0; n < perThread; n++) {
                        ledger.recordServed("run-1", "game-x.bin", 3);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(ledger.bytes("run-1", "game-x.bin")).isEqualTo(3L * threads * perThread);
        assertThat(ledger.requests("run-1", "game-x.bin")).isEqualTo((long) threads * perThread);
    }

    @Test
    void aOneSecondWindowIsThePeakNotTheTotal() {
        AtomicLong clock = new AtomicLong();
        OriginByteLedger ledger = new OriginByteLedger(clock::get);

        ledger.recordServed("run-1", "game-x.bin", 100);
        ledger.recordServed("run-1", "game-x.bin", 50);
        clock.set(1_000);
        ledger.recordServed("run-1", "game-x.bin", 40);

        assertThat(ledger.bytes("run-1", "game-x.bin")).isEqualTo(190);
        assertThat(ledger.peakBytes("run-1", "game-x.bin")).isEqualTo(150);
        assertThat(ledger.peakBytesForRun("run-1")).isEqualTo(150);
        assertThat(ledger.peakBytes("run-1", "missing.bin")).isZero();
        assertThat(ledger.peakBytesForRun("nobody")).isZero();
    }

    @Test
    void resetClearsEverything() {
        OriginByteLedger ledger = new OriginByteLedger();
        ledger.recordServed("run-1", "a.bin", 5);

        ledger.reset();

        assertThat(ledger.totalBytes()).isZero();
        assertThat(ledger.lines()).isEmpty();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
