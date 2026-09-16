package com.prabin.swarmedge.peer.session;

import com.prabin.swarmedge.protocol.ProtocolViolationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4-05: a cancelled request must stop work where it can, and data that arrives too
 * late must be ignored rather than treated as an attack or written to disk.
 */
class RequestTrackerTest {

    private static final int BLOCK = 1_024;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private long now;

    @Test
    void requestIdsAreNeverZeroBecauseZeroMeansUnsolicited() {
        RequestTracker tracker = tracker(8, 1 << 20);
        Set<Long> seen = new HashSet<>();

        for (int i = 0; i < 8; i++) {
            long id = tracker.issue(0, i * BLOCK, BLOCK).requestId();
            assertThat(id).isNotZero();
            assertThat(seen.add(id)).as("request id %s reused", id).isTrue();
        }
    }

    @Test
    void aBlockIsAcceptedOnlyAgainstTheRequestItAnswers() {
        RequestTracker tracker = tracker(4, 1 << 20);
        RequestTracker.Outstanding request = tracker.issue(2, 0, BLOCK);

        assertThat(tracker.accept(request.requestId(), 2, 0, BLOCK)).contains(request);
    }

    @Test
    void aBlockForAnIdWeNeverIssuedClosesTheConnection() {
        RequestTracker tracker = tracker(4, 1 << 20);
        tracker.issue(0, 0, BLOCK);

        assertThatThrownBy(() -> tracker.accept(9_999L, 0, 0, BLOCK))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("never issued");
    }

    @Test
    void aBlockThatAnswersTheWrongSpanIsAViolationNotAGift() {
        RequestTracker tracker = tracker(4, 1 << 20);
        long id = tracker.issue(3, BLOCK, BLOCK).requestId();

        assertThatThrownBy(() -> tracker.accept(id, 4, BLOCK, BLOCK))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("does not match request");
        assertThatThrownBy(() -> tracker.accept(id, 3, 0, BLOCK))
                .isInstanceOf(ProtocolViolationException.class);
        assertThatThrownBy(() -> tracker.accept(id, 3, BLOCK, BLOCK / 2))
                .isInstanceOf(ProtocolViolationException.class);
    }

    @Test
    void dataForACancelledRequestIsIgnoredQuietly() {
        RequestTracker tracker = tracker(4, 1 << 20);
        long id = tracker.issue(1, 0, BLOCK).requestId();

        assertThat(tracker.cancel(id)).isPresent();

        assertThat(tracker.accept(id, 1, 0, BLOCK)).isEmpty();
        assertThat(tracker.outstandingCount()).isZero();
        assertThat(tracker.outstandingBytes()).isZero();
    }

    @Test
    void cancellingTwiceIsHarmlessAndTheSecondCallReportsNothingToStop() {
        RequestTracker tracker = tracker(4, 1 << 20);
        long id = tracker.issue(1, 0, BLOCK).requestId();

        assertThat(tracker.cancel(id)).isPresent();
        assertThat(tracker.cancel(id)).isEmpty();
        assertThat(tracker.accept(id, 1, 0, BLOCK)).isEmpty();
    }

    @Test
    void aCompletedRequestFreesItsBudgetAndADuplicateBlockIsIgnored() {
        RequestTracker tracker = tracker(2, 1 << 20);
        long id = tracker.issue(0, 0, BLOCK).requestId();
        assertThat(tracker.outstandingBytes()).isEqualTo(BLOCK);

        tracker.complete(id);
        tracker.complete(id);

        assertThat(tracker.outstandingCount()).isZero();
        assertThat(tracker.outstandingBytes()).isZero();
        assertThat(tracker.accept(id, 0, 0, BLOCK)).isEmpty();
    }

    @Test
    void theRequestBudgetIsAlsoTheMemoryBudget() {
        RequestTracker tracker = tracker(2, 4 * BLOCK);

        tracker.issue(0, 0, BLOCK);
        tracker.issue(0, BLOCK, BLOCK);

        assertThat(tracker.hasCapacityFor(BLOCK)).isFalse();
        assertThatThrownBy(() -> tracker.issue(0, 2 * BLOCK, BLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget is full");
    }

    @Test
    void aByteBudgetCanBiteBeforeTheRequestCountDoes() {
        RequestTracker tracker = tracker(8, 3 * BLOCK);

        tracker.issue(0, 0, BLOCK);
        tracker.issue(0, BLOCK, BLOCK);
        tracker.issue(0, 2 * BLOCK, BLOCK);

        assertThat(tracker.outstandingCount()).isEqualTo(3);
        assertThat(tracker.hasCapacityFor(1)).isFalse();
    }

    @Test
    void aTimedOutRequestIsReportedOnceAndThenTreatedAsLateData() {
        RequestTracker tracker = tracker(4, 1 << 20);
        long id = tracker.issue(5, 0, BLOCK).requestId();

        now += TIMEOUT.toNanos() - 1;
        assertThat(tracker.expire()).isEmpty();

        now += 1;
        List<RequestTracker.Outstanding> timedOut = tracker.expire();

        assertThat(timedOut).extracting(RequestTracker.Outstanding::requestId).containsExactly(id);
        assertThat(tracker.expire()).isEmpty();
        assertThat(tracker.accept(id, 5, 0, BLOCK)).isEmpty();
        assertThat(tracker.hasCapacityFor(BLOCK)).isTrue();
    }

    @Test
    void onlyTheRequestsPastTheirDeadlineExpire() {
        RequestTracker tracker = tracker(4, 1 << 20);
        long early = tracker.issue(0, 0, BLOCK).requestId();
        now += TIMEOUT.toNanos() / 2;
        long late = tracker.issue(0, BLOCK, BLOCK).requestId();

        now += TIMEOUT.toNanos() / 2;
        List<RequestTracker.Outstanding> timedOut = tracker.expire();

        assertThat(timedOut).extracting(RequestTracker.Outstanding::requestId).containsExactly(early);
        assertThat(tracker.isOutstanding(late)).isTrue();
    }

    @Test
    void deadlinesSurviveANegativeClock() {
        now = Long.MAX_VALUE - TIMEOUT.toNanos() / 2;
        RequestTracker tracker = tracker(4, 1 << 20);
        long id = tracker.issue(0, 0, BLOCK).requestId();

        now += TIMEOUT.toNanos();

        assertThat(tracker.expire()).extracting(RequestTracker.Outstanding::requestId).containsExactly(id);
    }

    @Test
    void drainingDropsEveryOpenRequestAtOnce() {
        RequestTracker tracker = tracker(4, 1 << 20);
        tracker.issue(0, 0, BLOCK);
        tracker.issue(0, BLOCK, BLOCK);

        List<RequestTracker.Outstanding> dropped = tracker.cancelAll();

        assertThat(dropped).hasSize(2);
        assertThat(tracker.outstandingCount()).isZero();
        assertThat(tracker.outstandingBytes()).isZero();
        for (RequestTracker.Outstanding request : dropped) {
            assertThat(tracker.accept(request.requestId(), 0, request.blockOffset(), BLOCK)).isEmpty();
        }
    }

    @Test
    void veryOldIdsAreEventuallyForgottenSoMemoryStaysBounded() {
        RequestTracker tracker = tracker(1, 1 << 20);
        long first = tracker.issue(0, 0, BLOCK).requestId();
        tracker.complete(first);

        // Retired memory is bounded; churn far past it.
        for (int i = 0; i < 500; i++) {
            tracker.complete(tracker.issue(0, 0, BLOCK).requestId());
        }

        assertThatThrownBy(() -> tracker.accept(first, 0, 0, BLOCK))
                .isInstanceOf(ProtocolViolationException.class);
    }

    @Test
    void refusesNonsensicalConfiguration() {
        assertThatThrownBy(() -> new RequestTracker(0, 1 << 20, TIMEOUT, this::clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestTracker(4, 0, TIMEOUT, this::clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestTracker(4, 1 << 20, Duration.ZERO, this::clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aZeroLengthRequestIsRefused() {
        RequestTracker tracker = tracker(4, 1 << 20);

        assertThatThrownBy(() -> tracker.issue(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOutstandingRequestRemembersWhenItWasIssued() {
        now = 1_000;
        RequestTracker tracker = tracker(4, 1 << 20);

        RequestTracker.Outstanding request = tracker.issue(0, 0, BLOCK);

        assertThat(request.issuedAtNanos()).isEqualTo(1_000L);
        assertThat(request.deadlineNanos()).isEqualTo(1_000L + TIMEOUT.toNanos());
    }

    private RequestTracker tracker(int maxOutstanding, long maxBytes) {
        return new RequestTracker(maxOutstanding, maxBytes, TIMEOUT, this::clock);
    }

    private long clock() {
        return now;
    }
}
