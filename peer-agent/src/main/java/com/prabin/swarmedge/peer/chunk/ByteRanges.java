package com.prabin.swarmedge.peer.chunk;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Which byte ranges of a chunk have landed so far.
 *
 * <p>Tracking ranges rather than block numbers keeps the assembler honest about what
 * it actually wrote. Blocks happen to be aligned today because the leecher picks the
 * offsets, but nothing here depends on that, so a duplicate, overlapping, or
 * differently sized block is still accounted for correctly.
 *
 * <p>Ranges are half-open and merged on insert, so a chunk is complete exactly when
 * one range spans it.
 */
final class ByteRanges {

    private final NavigableMap<Long, Long> ranges = new TreeMap<>();

    void add(long start, long endExclusive) {
        if (start < 0) {
            throw new IllegalArgumentException("start must be non-negative: " + start);
        }
        if (endExclusive <= start) {
            throw new IllegalArgumentException("range must not be empty: " + start + ".." + endExclusive);
        }
        long from = start;
        long to = endExclusive;

        // Grow leftwards if the range below touches or overlaps this one.
        Map.Entry<Long, Long> below = ranges.floorEntry(from);
        if (below != null && below.getValue() >= from) {
            from = below.getKey();
            to = Math.max(to, below.getValue());
        }
        // Then swallow every range that this one now reaches.
        var following = ranges.tailMap(from, true).entrySet().iterator();
        while (following.hasNext()) {
            Map.Entry<Long, Long> next = following.next();
            if (next.getKey() > to) {
                break;
            }
            to = Math.max(to, next.getValue());
            following.remove();
        }
        ranges.put(from, to);
    }

    boolean covers(long start, long endExclusive) {
        if (endExclusive <= start) {
            return true;
        }
        Map.Entry<Long, Long> below = ranges.floorEntry(start);
        return below != null && below.getValue() >= endExclusive;
    }

    long coveredBytes() {
        long total = 0;
        for (Map.Entry<Long, Long> range : ranges.entrySet()) {
            total += range.getValue() - range.getKey();
        }
        return total;
    }

    int fragmentCount() {
        return ranges.size();
    }

    boolean isEmpty() {
        return ranges.isEmpty();
    }

    void clear() {
        ranges.clear();
    }
}
