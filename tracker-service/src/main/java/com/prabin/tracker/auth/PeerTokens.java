package com.prabin.tracker.auth;

import com.prabin.swarmedge.common.auth.HmacPeerToken;
import com.prabin.swarmedge.common.id.PeerId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Short-lived peer tokens for the research prototype (blueprint §11.3).
 *
 * <p>A token binds a {@link PeerId} to its site policy for a few minutes. It is
 * an identity hint for the control plane only: it never authorizes content. A
 * valid token still cannot make a bad chunk acceptable, and an expired one
 * cannot invalidate a chunk that already hashed correctly.
 *
 * <p>Production is expected to replace this with mTLS. The shape of the claims
 * is the part meant to survive that change.
 */
public final class PeerTokens {

    public static final Duration DEFAULT_TTL = HmacPeerToken.DEFAULT_TTL;
    static final String VERSION = HmacPeerToken.VERSION;

    private final HmacPeerToken tokens;

    public PeerTokens(byte[] secret, Duration ttl, Clock clock) {
        this.tokens = new HmacPeerToken(secret, ttl, clock);
    }

    public Duration ttl() {
        return tokens.ttl();
    }

    public Issued issue(PeerId peerId, String siteId, String networkGroupId) {
        HmacPeerToken.Issued issued = tokens.issue(peerId, siteId, networkGroupId);
        return new Issued(issued.token(), issued.expiresAt(), issued.ttlSeconds());
    }

    /**
     * Fail closed: a malformed, re-signed, or expired token is rejected the same way.
     * The caller learns that the token is unusable, not why, so probing gains nothing.
     */
    public Claims verify(String token) {
        try {
            HmacPeerToken.Claims claims = tokens.verify(token);
            return new Claims(claims.peerId(), claims.siteId(), claims.networkGroupId(), claims.expiresAt());
        } catch (HmacPeerToken.InvalidTokenException e) {
            throw new InvalidTokenException(e.getMessage());
        }
    }

    /** The announcing peer must be the token's subject and carry its site policy. */
    public Claims verifyFor(String token, PeerId peerId, String siteId, String networkGroupId) {
        Claims claims = verify(token);
        if (!claims.peerId().equals(peerId)) {
            throw new InvalidTokenException("token subject does not match the announcing peer");
        }
        if (!claims.siteId().equals(siteId) || !claims.networkGroupId().equals(networkGroupId)) {
            throw new InvalidTokenException("token does not carry the announced site policy");
        }
        return claims;
    }

    public record Issued(String token, Instant expiresAt, long ttlSeconds) {
    }

    public record Claims(PeerId peerId, String siteId, String networkGroupId, Instant expiresAt) {
    }

    public static final class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
