package com.prabin.swarmedge.peer.swarm;

import com.prabin.swarmedge.protocol.ProtocolViolationException;
import com.prabin.swarmedge.protocol.msg.ChunkBitfield;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkAvailabilityTest {

    private static final int CHUNKS = 6;
    private static final long SEED = 20260914L;
    private static final IntPredicate WANTS_EVERYTHING = index -> true;

    @Test
    void aChunkNoConnectedPeerHoldsIsNeverOffered() {
        ChunkAvailability availability = new ChunkAvailability(CHUNKS, SEED);

        availability.join(1, bits(0, 1));

        assertThat(availability.rarestFirst(WANTS_EVERYTHING)).containsExactlyInAnyOrder(0, 1);
        assertThat(availability.reachable(5)).isFalse();
        assertThat(availability.holders(5)).isZero();
    }

    @Test
    void theScarcestChunkComesFirst() {
        ChunkAvailability availability = new ChunkAvailability(CHUNKS, SEED);
        availability.join(1, bits(0, 1, 2));
        availability.join(2, bits(0, 1));
        availability.join(3, bits(0));

        // Chunk 2 has one holder, chunk 1 has two, chunk 0 has three.
        assertThat(availability.rarestFirst(WANTS_EVERYTHING)).containsExactly(2, 1, 0);
        assertThat(availability.holders(0)).isEqualTo(3);
    }

    @Test
    void aChunkWeAlreadyHaveIsNotAskedForAgain() {
        ChunkAvailability availability = new ChunkAvailability(CHUNKS, SEED);
        availability.join(1, bits(0, 1, 2, 3));

        assertThat(availability.rarestFirst(index -> index != 1 && index != 2)).containsExactly(0, 3);
    }

    @Test
    void aVerifiedChunkBecomesDiscoverableThroughHave() {
        ChunkAvailability availability = new ChunkAvailability(CHUNKS, SEED);
        availability.join(1, bits(0));

        assertThat(availability.reachable(4)).isFalse();

        availability.note(1, 4);

        assertThat(availability.reachable(4)).isTrue();
        assertThat(availability.holders(4)).isEqualTo(1);
    }

    @Test
    void aRepeatedHaveDoesNotInflateScarcity() {
        ChunkAvailability availability = new ChunkAvailability(CHUNKS, SEED);
        availability.join(1, bits(0));

        availability.note(1, 3);
        availability.note(1, 3);
        // Already in the bitfield it joined with, so this changes nothing either.
        availability.note(1, 0);

        assertThat(availability.holders(3)).isEqualTo(1);
        assertThat(availability.holders(0)).isEqualTo(1);
    }

    @Test
    void holdersOfNamesTheSessionsNotJustTheCount() {
        ChunkAvailability availability = new ChunkAvailability(CHUNKS, SEED);
        availability.join(1, bits(0, 1));
        availability.join(2, bits(1, 2));

        assertThat(availability.holdersOf(1)).containsExactlyInAnyOrder(1, 2);
        assertThat(availability.holdersOf(0)).containsExactly(1);
        assertThat(availability.holdersOf(5)).isEmpty();
    }

    @Test
    void aDroppedPeerTakesItsWholeInventoryWithIt() {
        ChunkAvailability availability = new ChunkAvailability(CHUNKS, SEED);
        availability.join(1, bits(0, 1));
        availability.join(2, bits(1));
        availability.note(1, 2);

        availability.leave(1);

        assertThat(availability.holders(0)).isZero();
        assertThat(availability.holders(1)).isEqualTo(1);
        assertThat(availability.holders(2)).isZero();
        assertThat(availability.connectedPeers()).isEqualTo(1);
        assertThat(availability.rarestFirst(WANTS_EVERYTHING)).containsExactly(1);
    }

    @Test
    void droppingTheSamePeerTwiceDoesNotDriveCountsNegative() {
        ChunkAvailability availability = new ChunkAvailability(CHUNKS, SEED);
        availability.join(1, bits(0));

        availability.leave(1);
        availability.leave(1);
        // A peer that dropped before it ever sent a BITFIELD is the same story.
        availability.leave(99);

        assertThat(availability.holders(0)).isZero();
        assertThat(availability.connectedPeers()).isZero();
    }

    @Test
    void aReconnectingPeerIsCountedOnceNotTwice() {
        ChunkAvailability availability = new ChunkAvailability(CHUNKS, SEED);

        availability.join(1, bits(0, 1));
        availability.join(1, bits(0));

        assertThat(availability.holders(0)).isEqualTo(1);
        assertThat(availability.holders(1)).isZero();
        assertThat(availability.connectedPeers()).isEqualTo(1);
    }

    @Test
    void anEqualSwarmStillProducesAStableOrderSoRunsCanBeReplayed() {
        ChunkAvailability first = new ChunkAvailability(CHUNKS, SEED);
        ChunkAvailability second = new ChunkAvailability(CHUNKS, SEED);
        first.join(1, bits(0, 1, 2, 3, 4, 5));
        second.join(1, bits(0, 1, 2, 3, 4, 5));

        List<Integer> order = first.rarestFirst(WANTS_EVERYTHING);

        assertThat(order).containsExactlyElementsOf(second.rarestFirst(WANTS_EVERYTHING));
        assertThat(order).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5);
    }

    @Test
    void aDifferentSeedSpreadsAnEqualSwarmDifferently() {
        ChunkAvailability seeded = new ChunkAvailability(64, 1L);
        ChunkAvailability otherwiseSeeded = new ChunkAvailability(64, 2L);
        byte[] everything = new byte[8];
        for (int i = 0; i < 64; i++) {
            ChunkBitfield.set(everything, i);
        }
        seeded.join(1, everything);
        otherwiseSeeded.join(1, everything);

        // Not index order, and not the same order as another seed: eight peers starting
        // together must not all chase chunk 0.
        assertThat(seeded.rarestFirst(WANTS_EVERYTHING).getFirst()).isNotZero();
        assertThat(seeded.rarestFirst(WANTS_EVERYTHING))
                .isNotEqualTo(otherwiseSeeded.rarestFirst(WANTS_EVERYTHING));
    }

    @Test
    void scarcityBeatsTheTieBreak() {
        ChunkAvailability availability = new ChunkAvailability(CHUNKS, SEED);
        availability.join(1, bits(0, 1, 2, 3, 4, 5));
        List<Integer> equalOrder = availability.rarestFirst(WANTS_EVERYTHING);
        int tieBreakFavourite = equalOrder.getFirst();

        // Give the tie-break favourite a second holder. It is no longer the scarcest, so
        // it goes to the back and the next chunk in the seeded order takes over.
        availability.join(2, bits(tieBreakFavourite));

        List<Integer> after = availability.rarestFirst(WANTS_EVERYTHING);
        assertThat(after.getLast()).isEqualTo(tieBreakFavourite);
        assertThat(after.getFirst()).isEqualTo(equalOrder.get(1));
    }

    @Test
    void aBitfieldThatDoesNotDescribeThisAssetIsRefused() {
        ChunkAvailability availability = new ChunkAvailability(CHUNKS, SEED);

        assertThatThrownBy(() -> availability.join(1, new byte[2]))
                .isInstanceOf(ProtocolViolationException.class);
        // Padding bits would make two peers disagree about the same inventory.
        assertThatThrownBy(() -> availability.join(1, new byte[]{(byte) 0xFF}))
                .isInstanceOf(ProtocolViolationException.class);
        assertThat(availability.connectedPeers()).isZero();
    }

    @Test
    void aHaveBeforeABitfieldIsAProgrammingErrorNotASilentCount() {
        ChunkAvailability availability = new ChunkAvailability(CHUNKS, SEED);

        assertThatThrownBy(() -> availability.note(1, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before BITFIELD");
    }

    @Test
    void anEmptyAssetHasNothingToRank() {
        ChunkAvailability availability = new ChunkAvailability(0, SEED);

        availability.join(1, new byte[0]);

        assertThat(availability.rarestFirst(WANTS_EVERYTHING)).isEmpty();
        assertThatThrownBy(() -> availability.holders(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bits(int... chunkIndexes) {
        byte[] bits = ChunkBitfield.empty(CHUNKS);
        for (int index : chunkIndexes) {
            ChunkBitfield.set(bits, index);
        }
        return bits;
    }
}
