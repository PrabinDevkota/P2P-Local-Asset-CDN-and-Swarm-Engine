package com.prabin.tracker.auth;

import com.prabin.swarmedge.common.id.PeerId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeerTokensTest {

    private static final Instant T0 = Instant.parse("2026-09-12T00:00:00Z");
    private static final PeerId PEER = PeerId.fromHex("0123456789abcdef0123456789abcdef");
    private static final PeerId OTHER_PEER = PeerId.fromHex("fedcba9876543210fedcba9876543210");
    private static final byte[] SECRET = "a-32-byte-or-longer-tracker-secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_SECRET = "a-different-32-byte-tracker-secret".getBytes(StandardCharsets.UTF_8);

    @Test
    void issuedTokenVerifiesAndCarriesItsClaims() {
        PeerTokens tokens = at(T0);

        PeerTokens.Issued issued = tokens.issue(PEER, "site-a", "ng-1");

        assertThat(issued.expiresAt()).isEqualTo(T0.plus(PeerTokens.DEFAULT_TTL));
        assertThat(issued.ttlSeconds()).isEqualTo(300);
        PeerTokens.Claims claims = tokens.verify(issued.token());
        assertThat(claims.peerId()).isEqualTo(PEER);
        assertThat(claims.siteId()).isEqualTo("site-a");
        assertThat(claims.networkGroupId()).isEqualTo("ng-1");
    }

    @Test
    void anExpiredTokenIsRejected() {
        String token = at(T0).issue(PEER, "site-a", "ng-1").token();

        PeerTokens later = at(T0.plus(PeerTokens.DEFAULT_TTL));

        assertThatThrownBy(() -> later.verify(token))
                .isInstanceOf(PeerTokens.InvalidTokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void aTokenStillWorksOneSecondBeforeItExpires() {
        String token = at(T0).issue(PEER, "site-a", "ng-1").token();

        PeerTokens later = at(T0.plus(PeerTokens.DEFAULT_TTL).minusSeconds(1));

        assertThat(later.verify(token).peerId()).isEqualTo(PEER);
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        String token = at(T0).issue(PEER, "site-a", "ng-1").token();

        PeerTokens stranger = new PeerTokens(OTHER_SECRET, PeerTokens.DEFAULT_TTL, fixed(T0));

        assertThatThrownBy(() -> stranger.verify(token))
                .isInstanceOf(PeerTokens.InvalidTokenException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void editingTheClaimsWithoutResigningIsRejected() {
        PeerTokens tokens = at(T0);
        String token = tokens.issue(PEER, "site-a", "ng-1").token();
        String mac = token.substring(token.indexOf('.'));
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (PeerTokens.VERSION + "|" + PEER.toHex() + "|site-secret|ng-1|"
                        + T0.plusSeconds(99999).getEpochSecond()).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> tokens.verify(forgedPayload + mac))
                .isInstanceOf(PeerTokens.InvalidTokenException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void malformedTokensAreRejectedWithoutLeakingDetail() {
        PeerTokens tokens = at(T0);

        assertThatThrownBy(() -> tokens.verify(null)).isInstanceOf(PeerTokens.InvalidTokenException.class);
        assertThatThrownBy(() -> tokens.verify("")).isInstanceOf(PeerTokens.InvalidTokenException.class);
        assertThatThrownBy(() -> tokens.verify("no-dot")).isInstanceOf(PeerTokens.InvalidTokenException.class);
        assertThatThrownBy(() -> tokens.verify(".onlymac")).isInstanceOf(PeerTokens.InvalidTokenException.class);
        assertThatThrownBy(() -> tokens.verify("payload.")).isInstanceOf(PeerTokens.InvalidTokenException.class);
        assertThatThrownBy(() -> tokens.verify("a.b.c")).isInstanceOf(PeerTokens.InvalidTokenException.class);
        assertThatThrownBy(() -> tokens.verify("!!!.!!!")).isInstanceOf(PeerTokens.InvalidTokenException.class);
    }

    @Test
    void aTokenIssuedForAnotherPeerCannotAnnounce() {
        PeerTokens tokens = at(T0);
        String token = tokens.issue(OTHER_PEER, "site-a", "ng-1").token();

        assertThatThrownBy(() -> tokens.verifyFor(token, PEER, "site-a", "ng-1"))
                .isInstanceOf(PeerTokens.InvalidTokenException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void aPeerCannotAnnounceALocalityItsTokenDoesNotGrant() {
        PeerTokens tokens = at(T0);
        String token = tokens.issue(PEER, "site-a", "ng-1").token();

        assertThatThrownBy(() -> tokens.verifyFor(token, PEER, "site-b", "ng-1"))
                .isInstanceOf(PeerTokens.InvalidTokenException.class)
                .hasMessageContaining("site policy");
        assertThatThrownBy(() -> tokens.verifyFor(token, PEER, "site-a", "ng-2"))
                .isInstanceOf(PeerTokens.InvalidTokenException.class)
                .hasMessageContaining("site policy");
        assertThat(tokens.verifyFor(token, PEER, "site-a", "ng-1").peerId()).isEqualTo(PEER);
    }

    @Test
    void aShortSecretIsRefusedAtStartup() {
        assertThatThrownBy(() -> new PeerTokens("too-short".getBytes(StandardCharsets.UTF_8),
                PeerTokens.DEFAULT_TTL, fixed(T0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void anInvalidLocalityLabelCannotBeIssued() {
        PeerTokens tokens = at(T0);

        assertThatThrownBy(() -> tokens.issue(PEER, "site a", "ng-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.issue(PEER, "", "ng-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PeerTokens at(Instant instant) {
        return new PeerTokens(SECRET, PeerTokens.DEFAULT_TTL, fixed(instant));
    }

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void ttlIsConfigurable() {
        PeerTokens tokens = new PeerTokens(SECRET, Duration.ofSeconds(30), fixed(T0));

        assertThat(tokens.ttl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(tokens.issue(PEER, "site-a", "ng-1").expiresAt()).isEqualTo(T0.plusSeconds(30));
    }
}
