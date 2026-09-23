package com.prabin.swarmedge.peer.session;

import com.prabin.swarmedge.common.auth.HmacPeerToken;
import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class HmacPeerAuthTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");
    private static final byte[] SECRET = "a-32-byte-or-longer-tracker-secret".getBytes(StandardCharsets.UTF_8);
    private static final PeerId PEER = PeerId.fromHex("ab".repeat(16));
    private static final AssetId ASSET = AssetId.fromHex("cd".repeat(32));

    @Test
    void aTokenForThisPeerIsAccepted() {
        String token = issuer().issue(PEER, "site-a", "ng-1").token();
        HmacPeerAuth auth = new HmacPeerAuth(SECRET, Clock.fixed(T0, ZoneOffset.UTC));

        assertThat(auth.check(ASSET, PEER, token.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PeerAuthPolicy.ACCEPTED);
    }

    @Test
    void aTokenForAnotherPeerIsRejected() {
        PeerId other = PeerId.fromHex("11".repeat(16));
        String token = issuer().issue(other, "site-a", "ng-1").token();
        HmacPeerAuth auth = new HmacPeerAuth(SECRET, Clock.fixed(T0, ZoneOffset.UTC));

        assertThat(auth.check(ASSET, PEER, token.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PeerAuthPolicy.TOKEN_REJECTED);
    }

    @Test
    void anExpiredTokenIsRejected() {
        String token = issuer().issue(PEER, "site-a", "ng-1").token();
        HmacPeerAuth auth = new HmacPeerAuth(SECRET, Clock.fixed(T0.plus(Duration.ofHours(1)), ZoneOffset.UTC));

        assertThat(auth.check(ASSET, PEER, token.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PeerAuthPolicy.TOKEN_REJECTED);
    }

    @Test
    void garbageIsRejected() {
        HmacPeerAuth auth = new HmacPeerAuth(SECRET, Clock.fixed(T0, ZoneOffset.UTC));

        assertThat(auth.check(ASSET, PEER, "phase-5-dev-token".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PeerAuthPolicy.TOKEN_REJECTED);
    }

    private static HmacPeerToken issuer() {
        return new HmacPeerToken(SECRET, HmacPeerToken.DEFAULT_TTL, Clock.fixed(T0, ZoneOffset.UTC));
    }
}
