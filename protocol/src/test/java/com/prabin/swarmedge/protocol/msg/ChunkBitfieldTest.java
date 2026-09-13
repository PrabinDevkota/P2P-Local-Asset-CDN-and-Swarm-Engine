package com.prabin.swarmedge.protocol.msg;

import com.prabin.swarmedge.protocol.ProtocolViolationException;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkBitfieldTest {

    @Test
    void theFirstChunkIsTheHighBitOfTheFirstByte() {
        byte[] bits = ChunkBitfield.empty(8);

        ChunkBitfield.set(bits, 0);

        assertThat(HexFormat.of().formatHex(bits)).isEqualTo("80");
        assertThat(ChunkBitfield.get(bits, 0)).isTrue();
        assertThat(ChunkBitfield.get(bits, 1)).isFalse();
    }

    @Test
    void chunksReadLeftToRightInAHexDump() {
        byte[] bits = ChunkBitfield.empty(16);

        ChunkBitfield.set(bits, 0);
        ChunkBitfield.set(bits, 1);
        ChunkBitfield.set(bits, 8);
        ChunkBitfield.set(bits, 15);

        assertThat(HexFormat.of().formatHex(bits)).isEqualTo("c081");
        assertThat(ChunkBitfield.cardinality(bits, 16)).isEqualTo(4);
    }

    @Test
    void settingTheSameChunkTwiceChangesNothing() {
        byte[] bits = ChunkBitfield.empty(8);

        ChunkBitfield.set(bits, 3);
        ChunkBitfield.set(bits, 3);

        assertThat(ChunkBitfield.cardinality(bits, 8)).isEqualTo(1);
    }

    @Test
    void sizeFollowsTheChunkCountNotTheByteBoundary() {
        assertThat(ChunkBitfield.byteLength(0)).isZero();
        assertThat(ChunkBitfield.byteLength(1)).isEqualTo(1);
        assertThat(ChunkBitfield.byteLength(8)).isEqualTo(1);
        assertThat(ChunkBitfield.byteLength(9)).isEqualTo(2);
        assertThat(ChunkBitfield.empty(17)).hasSize(3);
    }

    @Test
    void aWrongSizedBitfieldIsRejected() {
        assertThatThrownBy(() -> ChunkBitfield.validate(new byte[2], 8))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("must be 1 bytes");
        assertThatThrownBy(() -> ChunkBitfield.validate(new byte[1], 9))
                .isInstanceOf(ProtocolViolationException.class);
    }

    @Test
    void aSetPaddingBitIsAViolationSoTwoPeersCannotDisagreeOnTheSameInventory() {
        byte[] bits = ChunkBitfield.empty(5);
        ChunkBitfield.set(bits, 0);
        ChunkBitfield.validate(bits, 5);

        ChunkBitfield.set(bits, 7);

        assertThatThrownBy(() -> ChunkBitfield.validate(bits, 5))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("padding");
    }

    @Test
    void aFullByteHasNoPaddingToComplainAbout() {
        byte[] bits = ChunkBitfield.empty(8);
        for (int i = 0; i < 8; i++) {
            ChunkBitfield.set(bits, i);
        }

        ChunkBitfield.validate(bits, 8);

        assertThat(HexFormat.of().formatHex(bits)).isEqualTo("ff");
        assertThat(ChunkBitfield.cardinality(bits, 8)).isEqualTo(8);
    }

    @Test
    void anEmptyAssetHasAnEmptyBitfield() {
        byte[] bits = ChunkBitfield.empty(0);

        ChunkBitfield.validate(bits, 0);

        assertThat(bits).isEmpty();
    }

    @Test
    void readingOutsideTheBitfieldIsAProgrammingErrorNotASilentFalse() {
        byte[] bits = ChunkBitfield.empty(8);

        assertThatThrownBy(() -> ChunkBitfield.get(bits, 8))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ChunkBitfield.set(bits, -1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void theGoldenVectorBitfieldStillMeansChunkZero() {
        byte[] bits = HexFormat.of().parseHex("80");

        ChunkBitfield.validate(bits, 8);

        assertThat(ChunkBitfield.get(bits, 0)).isTrue();
        assertThat(Messages.bitfield(8, bits, 0).bits()).containsExactly(bits);
    }
}
