package com.prabin.swarmedge.peer.session;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.protocol.MessageType;
import com.prabin.swarmedge.protocol.ProtocolLimits;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import com.prabin.swarmedge.protocol.msg.ChunkBitfield;

import java.util.Objects;
import java.util.Optional;

/**
 * State of one connection: where the handshake has got to, what was negotiated, and
 * what the far side says it holds.
 *
 * <p>Handlers ask this object whether a frame is allowed before acting on it, so the
 * rule "a BLOCK is only meaningful once the session is ACTIVE" lives in one place
 * instead of being re-derived in every branch.
 *
 * <p>Confined to the channel's event loop; not thread-safe by design.
 */
public final class PeerSession {

    public enum Role {
        /** Dials out and asks for blocks. */
        LEECHER,
        /** Accepts connections and serves verified chunks. */
        SEEDER
    }

    private final Role role;
    private final AssetId assetId;
    private final PeerId localPeerId;
    private final int chunkCount;
    private final int preferredMaxBlockSize;

    private SessionState state = SessionState.DISCONNECTED;
    private PeerId remotePeerId;
    private int maxBlockSize;
    private byte[] remoteBitfield;
    private boolean bitfieldSent;
    private boolean bitfieldReceived;
    private int violations;

    public PeerSession(Role role, AssetId assetId, PeerId localPeerId, int chunkCount, int preferredMaxBlockSize) {
        this.role = Objects.requireNonNull(role, "role");
        this.assetId = Objects.requireNonNull(assetId, "assetId");
        this.localPeerId = Objects.requireNonNull(localPeerId, "localPeerId");
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount must be non-negative");
        }
        if (preferredMaxBlockSize <= 0 || preferredMaxBlockSize > ProtocolLimits.absoluteMaxBlockSize()) {
            throw new IllegalArgumentException("preferredMaxBlockSize out of range: " + preferredMaxBlockSize);
        }
        this.chunkCount = chunkCount;
        this.preferredMaxBlockSize = preferredMaxBlockSize;
        this.maxBlockSize = preferredMaxBlockSize;
    }

    public Role role() {
        return role;
    }

    public AssetId assetId() {
        return assetId;
    }

    public PeerId localPeerId() {
        return localPeerId;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public SessionState state() {
        return state;
    }

    public int maxBlockSize() {
        return maxBlockSize;
    }

    public int preferredMaxBlockSize() {
        return preferredMaxBlockSize;
    }

    public Optional<PeerId> remotePeerId() {
        return Optional.ofNullable(remotePeerId);
    }

    public int violations() {
        return violations;
    }

    public void transitionTo(SessionState next) {
        Objects.requireNonNull(next, "next");
        if (!state.canTransitionTo(next)) {
            throw new ProtocolViolationException("illegal session transition " + state + " -> " + next);
        }
        state = next;
    }

    /** A frame that does not belong in the current state is a protocol violation. */
    public void require(SessionState required, MessageType type) {
        if (state != required) {
            throw new ProtocolViolationException(type + " is not allowed in state " + state);
        }
    }

    public void requireActive(MessageType type) {
        if (!state.transfersData()) {
            throw new ProtocolViolationException(type + " is only allowed while ACTIVE, not " + state);
        }
    }

    public void noteViolation() {
        violations++;
    }

    /**
     * Settle on a block size both sides can live with. The smaller number wins, which
     * keeps one side from being told to accept more than it budgeted for.
     */
    public void negotiate(int remoteMaxBlockSize) {
        if (remoteMaxBlockSize <= 0 || remoteMaxBlockSize > ProtocolLimits.absoluteMaxBlockSize()) {
            throw new ProtocolViolationException("illegal negotiated maxBlockSize: " + remoteMaxBlockSize);
        }
        maxBlockSize = Math.min(preferredMaxBlockSize, remoteMaxBlockSize);
    }

    public void remotePeerId(PeerId peerId) {
        this.remotePeerId = Objects.requireNonNull(peerId, "peerId");
    }

    /**
     * Record the far side's inventory. A bit count that does not match our manifest
     * means the two peers do not agree on the asset, which is not something to
     * paper over.
     */
    public void acceptRemoteBitfield(int bitCount, byte[] bits) {
        if (bitCount != chunkCount) {
            throw new ProtocolViolationException(
                    "BITFIELD covers " + bitCount + " chunks but the asset has " + chunkCount);
        }
        ChunkBitfield.validate(bits, bitCount);
        remoteBitfield = bits.clone();
        bitfieldReceived = true;
    }

    public void noteBitfieldSent() {
        bitfieldSent = true;
    }

    public boolean remoteHas(int chunkIndex) {
        if (remoteBitfield == null) {
            return false;
        }
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new ProtocolViolationException("chunkIndex out of range: " + chunkIndex);
        }
        return ChunkBitfield.get(remoteBitfield, chunkIndex);
    }

    public int remoteChunkCount() {
        return remoteBitfield == null ? 0 : ChunkBitfield.cardinality(remoteBitfield, chunkCount);
    }

    /**
     * Move to ACTIVE once both inventories have crossed the wire. Called after every
     * BITFIELD event because the two can arrive in either order.
     *
     * @return true when this call is the one that opened the session for transfer
     */
    public boolean activateIfBitfieldsExchanged() {
        if (state != SessionState.AUTHENTICATED || !bitfieldSent || !bitfieldReceived) {
            return false;
        }
        transitionTo(SessionState.BITFIELD_EXCHANGED);
        transitionTo(SessionState.ACTIVE);
        return true;
    }
}
