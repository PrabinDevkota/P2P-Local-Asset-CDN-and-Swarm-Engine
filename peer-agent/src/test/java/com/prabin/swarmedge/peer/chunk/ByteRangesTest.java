package com.prabin.swarmedge.peer.chunk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByteRangesTest {

    @Test
    void blocksArrivingInOrderCollapseIntoOneRange() {
        ByteRanges ranges = new ByteRanges();

        ranges.add(0, 100);
        ranges.add(100, 200);
        ranges.add(200, 300);

        assertThat(ranges.fragmentCount()).isEqualTo(1);
        assertThat(ranges.covers(0, 300)).isTrue();
        assertThat(ranges.coveredBytes()).isEqualTo(300);
    }

    @Test
    void aHoleKeepsTheChunkIncompleteUntilItIsFilled() {
        ByteRanges ranges = new ByteRanges();

        ranges.add(0, 100);
        ranges.add(200, 300);

        assertThat(ranges.covers(0, 300)).isFalse();
        assertThat(ranges.coveredBytes()).isEqualTo(200);
        assertThat(ranges.fragmentCount()).isEqualTo(2);

        ranges.add(100, 200);

        assertThat(ranges.covers(0, 300)).isTrue();
        assertThat(ranges.fragmentCount()).isEqualTo(1);
    }

    @Test
    void aDuplicateBlockIsNotCountedTwice() {
        ByteRanges ranges = new ByteRanges();

        ranges.add(0, 100);
        ranges.add(0, 100);
        ranges.add(20, 60);

        assertThat(ranges.coveredBytes()).isEqualTo(100);
        assertThat(ranges.fragmentCount()).isEqualTo(1);
    }

    @Test
    void anOverlappingBlockExtendsRatherThanDoubleCounts() {
        ByteRanges ranges = new ByteRanges();

        ranges.add(0, 100);
        ranges.add(50, 150);

        assertThat(ranges.coveredBytes()).isEqualTo(150);
        assertThat(ranges.covers(0, 150)).isTrue();
    }

    @Test
    void oneRangeCanBridgeTwoIslands() {
        ByteRanges ranges = new ByteRanges();
        ranges.add(0, 100);
        ranges.add(400, 500);
        ranges.add(700, 800);

        ranges.add(50, 750);

        assertThat(ranges.fragmentCount()).isEqualTo(1);
        assertThat(ranges.covers(0, 800)).isTrue();
        assertThat(ranges.coveredBytes()).isEqualTo(800);
    }

    @Test
    void coverageIsAskedForAnExactSpanNotJustAnyOverlap() {
        ByteRanges ranges = new ByteRanges();
        ranges.add(100, 200);

        assertThat(ranges.covers(100, 200)).isTrue();
        assertThat(ranges.covers(120, 180)).isTrue();
        assertThat(ranges.covers(0, 200)).isFalse();
        assertThat(ranges.covers(150, 250)).isFalse();
    }

    @Test
    void blocksArrivingInAnyOrderStillCompleteTheChunk() {
        int blockSize = 4_096;
        int blocks = 64;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < blocks; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(20260913));

        ByteRanges ranges = new ByteRanges();
        for (int block : order) {
            ranges.add((long) block * blockSize, (long) (block + 1) * blockSize);
        }

        assertThat(ranges.fragmentCount()).isEqualTo(1);
        assertThat(ranges.covers(0, (long) blocks * blockSize)).isTrue();
    }

    @Test
    void emptyAndNegativeRangesAreProgrammingErrors() {
        ByteRanges ranges = new ByteRanges();

        assertThatThrownBy(() -> ranges.add(10, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ranges.add(10, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ranges.add(-1, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearingForgetsEverythingSoAChunkCanBeRetried() {
        ByteRanges ranges = new ByteRanges();
        ranges.add(0, 100);

        ranges.clear();

        assertThat(ranges.isEmpty()).isTrue();
        assertThat(ranges.coveredBytes()).isZero();
        assertThat(ranges.covers(0, 100)).isFalse();
    }

    @Test
    void offsetsBeyondTwoGigabytesAreHandledAsLongs() {
        ByteRanges ranges = new ByteRanges();
        long start = 8L * 1024 * 1024 * 1024;

        ranges.add(start, start + 4_194_304);

        assertThat(ranges.covers(start, start + 4_194_304)).isTrue();
        assertThat(ranges.coveredBytes()).isEqualTo(4_194_304);
    }
}
