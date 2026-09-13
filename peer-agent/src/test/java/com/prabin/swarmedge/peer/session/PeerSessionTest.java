package com.prabin.swarmedge.peer.session;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.protocol.MessageType;
import com.prabin.swarmedge.protocol.ProtocolLimits;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import com.prabin.swarmedge.protocol.msg.ChunkBitfield;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeerSessionTest {

    private static final int CHUNK_COUNT = 6;

    @Test
    void aSessionOnlyOpensForTransferAfterBothBitfieldsCross() {
        PeerSession session = session();
        session.transitionTo(SessionState.TCP_CONNECTED);
        session.transitionTo(SessionState.HELLO_SENT);
        session.transitionTo(SessionState.AUTHENTICATED);

        assertThat(session.activateIfBitfieldsExchanged()).isFalse();
        session.noteBitfieldSent();
        assertThat(session.activateIfBitfieldsExchanged()).isFalse();
        session.acceptRemoteBitfield(CHUNK_COUNT, bits(0, 1, 2));

        assertThat(session.activateIfBitfieldsExchanged()).isTrue();
        assertThat(session.state()).isEqualTo(SessionState.ACTIVE);
        // Only the call that opened the session reports true.
        assertThat(session.activateIfBitfieldsExchanged()).isFalse();
    }

    @Test
    void aFrameArrivingInTheWrongStateIsAViolation() {
        PeerSession session = session();

        assertThatThrownBy(() -> session.requireActive(MessageType.REQUEST))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("only allowed while ACTIVE");
        assertThatThrownBy(() -> session.require(SessionState.TCP_CONNECTED, MessageType.HELLO))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("not allowed in state DISCONNECTED");
    }

    @Test
    void skippingTheHandshakeIsRefusedByTheStateMachine() {
        PeerSession session = session();
        session.transitionTo(SessionState.TCP_CONNECTED);

        assertThatThrownBy(() -> session.transitionTo(SessionState.ACTIVE))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("illegal session transition");
    }

    @Test
    void theSmallerBlockSizeWins() {
        PeerSession session = session();
        assertThat(session.maxBlockSize()).isEqualTo(ProtocolLimits.defaultMaxBlockSize());

        session.negotiate(4_096);

        assertThat(session.maxBlockSize()).isEqualTo(4_096);
    }

    @Test
    void aLargerRemoteOfferDoesNotRaiseOurOwnBudget() {
        PeerSession session = new PeerSession(
                PeerSession.Role.LEECHER, assetId(), peerId(), CHUNK_COUNT, 8_192);

        session.negotiate(ProtocolLimits.absoluteMaxBlockSize());

        assertThat(session.maxBlockSize()).isEqualTo(8_192);
    }

    @Test
    void aNegotiatedBlockSizeOutsideTheProtocolIsRejected() {
        PeerSession session = session();

        assertThatThrownBy(() -> session.negotiate(0)).isInstanceOf(ProtocolViolationException.class);
        assertThatThrownBy(() -> session.negotiate(ProtocolLimits.absoluteMaxBlockSize() + 1))
                .isInstanceOf(ProtocolViolationException.class);
    }

    @Test
    void aBitfieldForADifferentAssetGeometryIsRejected() {
        PeerSession session = session();

        assertThatThrownBy(() -> session.acceptRemoteBitfield(CHUNK_COUNT + 1, new byte[1]))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("but the asset has 6");
    }

    @Test
    void aBitfieldWithPaddingBitsSetIsRejected() {
        byte[] bits = bits(0);
        ChunkBitfield.set(bits, 7);

        assertThatThrownBy(() -> session().acceptRemoteBitfield(CHUNK_COUNT, bits))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("padding");
    }

    @Test
    void beforeAnyBitfieldTheRemoteIsAssumedToHaveNothing() {
        PeerSession session = session();

        assertThat(session.remoteHas(0)).isFalse();
        assertThat(session.remoteChunkCount()).isZero();
    }

    @Test
    void theRemoteInventoryIsReadBackChunkByChunk() {
        PeerSession session = session();
        session.acceptRemoteBitfield(CHUNK_COUNT, bits(1, 4, 5));

        assertThat(session.remoteHas(1)).isTrue();
        assertThat(session.remoteHas(0)).isFalse();
        assertThat(session.remoteChunkCount()).isEqualTo(3);
        assertThatThrownBy(() -> session.remoteHas(CHUNK_COUNT))
                .isInstanceOf(ProtocolViolationException.class);
    }

    @Test
    void theStoredInventoryIsACopySoALaterEditCannotChangeIt() {
        PeerSession session = session();
        byte[] bits = bits(0);

        session.acceptRemoteBitfield(CHUNK_COUNT, bits);
        Arrays.fill(bits, (byte) 0);

        assertThat(session.remoteHas(0)).isTrue();
    }

    @Test
    void refusesConstructionWithLimitsThatCannotWork() {
        assertThatThrownBy(() -> new PeerSession(PeerSession.Role.SEEDER, assetId(), peerId(), -1, 1_024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeerSession(PeerSession.Role.SEEDER, assetId(), peerId(), 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeerSession(PeerSession.Role.SEEDER, assetId(), peerId(), 1,
                ProtocolLimits.absoluteMaxBlockSize() + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PeerSession session() {
        return new PeerSession(PeerSession.Role.LEECHER, assetId(), peerId(), CHUNK_COUNT,
                ProtocolLimits.defaultMaxBlockSize());
    }

    private static byte[] bits(int... chunkIndexes) {
        byte[] bits = ChunkBitfield.empty(CHUNK_COUNT);
        for (int index : chunkIndexes) {
            ChunkBitfield.set(bits, index);
        }
        return bits;
    }

    private static AssetId assetId() {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 0xAB);
        return AssetId.of(bytes);
    }

    private static PeerId peerId() {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, (byte) 0xCD);
        return PeerId.of(bytes);
    }
}
