package com.prabin.swarmedge.peer.session;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;

/**
 * Decides whether a HELLO may open a session.
 *
 * <p>This is a seam, not a security control. Phase 4 proves the data plane, and the
 * tracker already issues short-lived peer tokens, but the token in HELLO is not yet
 * verified against the issuing secret: doing that properly needs the mutual-TLS and
 * key-distribution work the blueprint schedules for the hardening phase. Keeping the
 * decision behind an interface means that work replaces one implementation rather than
 * editing the handshake.
 *
 * <p>Whatever the policy decides, it decides before any byte of any chunk is served.
 */
@FunctionalInterface
public interface PeerAuthPolicy {

    int ACCEPTED = 0;
    int UNKNOWN_ASSET = 1;
    int TOKEN_REJECTED = 2;
    int TOO_BUSY = 3;

    /** Development default: identity is checked, the token is not. */
    PeerAuthPolicy ACCEPT_ANY_TOKEN = (assetId, peerId, token) -> ACCEPTED;

    /** Refuses every session, for testing the rejection path. */
    PeerAuthPolicy REFUSE_ALL = (assetId, peerId, token) -> TOKEN_REJECTED;

    /**
     * @return {@link #ACCEPTED}, or a non-zero reason code to send back in HELLO_ACK
     */
    int check(AssetId assetId, PeerId peerId, byte[] token);
}
