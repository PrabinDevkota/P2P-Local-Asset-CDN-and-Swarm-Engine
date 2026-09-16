package com.prabin.swarmedge.peer.session;

import com.prabin.swarmedge.protocol.ProtocolViolationException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The requests this peer has outstanding on one connection (blueprint §7.4, §12.3).
 *
 * <p>Two rules make the receive path safe. A BLOCK is only accepted against a request
 * we actually issued, with the same chunk, offset, and length, so a peer cannot push
 * bytes we never asked for. And the number of open requests is capped, which is also
 * the memory cap: a peer can never have more data in flight towards us than the budget
 * allows, because unrequested data is dropped before it reaches disk.
 *
 * <p>Cancelled, completed, and timed-out ids are remembered for a while as
 * <em>retired</em>. That distinction matters: data for a retired id is late rather than
 * hostile, so it is ignored quietly, while data for an id that was never issued is a
 * protocol violation that closes the connection.
 *
 * <p>Confined to the session's event loop; not thread-safe by design.
 */
public final class RequestTracker {

    private static final int MIN_RETIRED_MEMORY = 64;

    private final int maxOutstanding;
    private final long maxOutstandingBytes;
    private final long timeoutNanos;
    private final LongSupplier nanoClock;
    private final int retiredMemory;

    private final Map<Long, Outstanding> outstanding = new LinkedHashMap<>();
    private final Set<Long> retired = new LinkedHashSet<>();

    private long outstandingBytes;
    private long nextRequestId = 1;

    public RequestTracker(int maxOutstanding, long maxOutstandingBytes, Duration timeout, LongSupplier nanoClock) {
        if (maxOutstanding <= 0) {
            throw new IllegalArgumentException("maxOutstanding must be positive");
        }
        if (maxOutstandingBytes <= 0) {
            throw new IllegalArgumentException("maxOutstandingBytes must be positive");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.maxOutstanding = maxOutstanding;
        this.maxOutstandingBytes = maxOutstandingBytes;
        this.timeoutNanos = timeout.toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.retiredMemory = Math.max(MIN_RETIRED_MEMORY, maxOutstanding * 16);
    }

    public int outstandingCount() {
        return outstanding.size();
    }

    public long outstandingBytes() {
        return outstandingBytes;
    }

    public boolean hasCapacityFor(int blockLength) {
        return outstanding.size() < maxOutstanding
                && outstandingBytes + blockLength <= maxOutstandingBytes;
    }

    public boolean isOutstanding(long requestId) {
        return outstanding.containsKey(requestId);
    }

    /** Reserve a request id and record what it is allowed to bring back. */
    public Outstanding issue(int chunkIndex, int blockOffset, int blockLength) {
        if (blockLength <= 0) {
            throw new IllegalArgumentException("blockLength must be positive");
        }
        if (!hasCapacityFor(blockLength)) {
            throw new IllegalStateException("request budget is full: " + outstanding.size()
                    + " requests, " + outstandingBytes + " bytes");
        }
        long issuedAt = nanoClock.getAsLong();
        Outstanding request = new Outstanding(
                allocateRequestId(), chunkIndex, blockOffset, blockLength,
                issuedAt, issuedAt + timeoutNanos);
        outstanding.put(request.requestId(), request);
        outstandingBytes += blockLength;
        return request;
    }

    /**
     * Match an inbound BLOCK header against what we asked for.
     *
     * @return the request it answers, or empty when the id is retired and the data is
     *         simply late
     * @throws ProtocolViolationException if the id was never issued, or the metadata
     *         does not match the request it claims to answer
     */
    public Optional<Outstanding> accept(long requestId, int chunkIndex, int blockOffset, int blockLength) {
        Outstanding request = outstanding.get(requestId);
        if (request == null) {
            if (retired.contains(requestId)) {
                return Optional.empty();
            }
            throw new ProtocolViolationException("BLOCK for a request we never issued: " + requestId);
        }
        if (request.chunkIndex() != chunkIndex
                || request.blockOffset() != blockOffset
                || request.blockLength() != blockLength) {
            throw new ProtocolViolationException("BLOCK does not match request " + requestId
                    + ": asked for " + request.describeSpan()
                    + " but got chunk " + chunkIndex + " [" + blockOffset + "+" + blockLength + "]");
        }
        return Optional.of(request);
    }

    /** The block arrived in full. Idempotent, so a duplicate END changes nothing. */
    public void complete(long requestId) {
        retire(requestId);
    }

    /**
     * Find an open request by what it asked for rather than by its id.
     *
     * <p>The endgame needs this: another peer delivered the same block, and the scheduler
     * knows which block to call off but not which request id this session used for it.
     */
    public Optional<Outstanding> findBySpan(int chunkIndex, int blockOffset, int blockLength) {
        for (Outstanding request : outstanding.values()) {
            if (request.chunkIndex() == chunkIndex
                    && request.blockOffset() == blockOffset
                    && request.blockLength() == blockLength) {
                return Optional.of(request);
            }
        }
        return Optional.empty();
    }

    /**
     * Give up on a request. Data that is already on its way becomes late data rather
     * than a violation, which is what lets a CANCEL race with a BLOCK safely.
     */
    public Optional<Outstanding> cancel(long requestId) {
        Outstanding request = outstanding.get(requestId);
        retire(requestId);
        return Optional.ofNullable(request);
    }

    /** Requests whose deadline has passed. They are retired, so their data is now late. */
    public List<Outstanding> expire() {
        long now = nanoClock.getAsLong();
        List<Outstanding> timedOut = new ArrayList<>();
        Iterator<Outstanding> requests = outstanding.values().iterator();
        while (requests.hasNext()) {
            Outstanding request = requests.next();
            // Subtraction, not comparison: nanoTime is allowed to be negative.
            if (now - request.deadlineNanos() >= 0) {
                timedOut.add(request);
                requests.remove();
                outstandingBytes -= request.blockLength();
                rememberRetired(request.requestId());
            }
        }
        return List.copyOf(timedOut);
    }

    /** Drop everything, for example when the session starts draining. */
    public List<Outstanding> cancelAll() {
        List<Outstanding> dropped = List.copyOf(outstanding.values());
        for (Outstanding request : dropped) {
            rememberRetired(request.requestId());
        }
        outstanding.clear();
        outstandingBytes = 0;
        return dropped;
    }

    private void retire(long requestId) {
        Outstanding request = outstanding.remove(requestId);
        if (request != null) {
            outstandingBytes -= request.blockLength();
        }
        rememberRetired(requestId);
    }

    private void rememberRetired(long requestId) {
        retired.add(requestId);
        // Bounded memory: the oldest retired ids are forgotten first. Data arriving for
        // an id that old is well past any timeout, so treating it as a violation is fair.
        Iterator<Long> oldest = retired.iterator();
        while (retired.size() > retiredMemory && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    private long allocateRequestId() {
        long id = nextRequestId;
        // requestId 0 means "unsolicited" on the wire, so it can never be handed out.
        nextRequestId = id == Long.MAX_VALUE ? 1 : id + 1;
        return id;
    }

    public record Outstanding(long requestId, int chunkIndex, int blockOffset, int blockLength,
                             long issuedAtNanos, long deadlineNanos) {

        String describeSpan() {
            return "chunk " + chunkIndex + " [" + blockOffset + "+" + blockLength + "]";
        }
    }
}
