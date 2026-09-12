package com.prabin.tracker.api;

import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.tracker.auth.PeerTokens;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Dev/research token issuance (blueprint §10.1). A real deployment authenticates
 * the peer first — mTLS or an enrolment service — and only then mints a token.
 * Here the tracker trusts the request, which is why the token is short lived and
 * grants nothing beyond announcing under one identity and one site policy.
 */
@RestController
public final class AuthController {

    private final PeerTokens tokens;

    public AuthController(PeerTokens tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    @PostMapping("/api/v1/auth/peer-token")
    public TokenResponse issue(@RequestBody TokenRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        PeerTokens.Issued issued = tokens.issue(
                parsePeerId(body.peerId()), body.siteId(), body.networkGroupId());
        return new TokenResponse(issued.token(), issued.expiresAt().toString(), issued.ttlSeconds());
    }

    private static PeerId parsePeerId(String hex) {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("peerId is required");
        }
        try {
            return PeerId.fromHex(hex.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("peerId must be 32 hex characters");
        }
    }

    public record TokenRequest(String peerId, String siteId, String networkGroupId) {
    }

    public record TokenResponse(String token, String expiresAt, long ttlSeconds) {
    }
}
