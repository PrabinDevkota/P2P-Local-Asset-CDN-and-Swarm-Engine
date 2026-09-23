package com.prabin.swarmedge.peer.session;

import com.prabin.swarmedge.common.auth.HmacPeerToken;
import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * HELLO check for the research HMAC token. A bad, expired, or mismatched token
 * is refused before any chunk byte is served. This is not mTLS.
 */
public final class HmacPeerAuth implements PeerAuthPolicy {

    private final HmacPeerToken tokens;

    public HmacPeerAuth(byte[] secret, Clock clock) {
        this.tokens = new HmacPeerToken(secret, Duration.ofMinutes(5), Objects.requireNonNull(clock, "clock"));
    }

    @Override
    public int check(AssetId assetId, PeerId peerId, byte[] token) {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(peerId, "peerId");
        if (token == null || token.length == 0) {
            return TOKEN_REJECTED;
        }
        try {
            HmacPeerToken.Claims claims = tokens.verify(new String(token, StandardCharsets.UTF_8));
            return claims.peerId().equals(peerId) ? ACCEPTED : TOKEN_REJECTED;
        } catch (HmacPeerToken.InvalidTokenException e) {
            return TOKEN_REJECTED;
        }
    }
}
