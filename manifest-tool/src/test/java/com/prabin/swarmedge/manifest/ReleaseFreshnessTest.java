package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseFreshnessTest {

    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void aCurrentSequenceIsAcceptedAndRecorded() throws Exception {
        ReleaseManifest current = signed("game-x", 17, "2026-10-12T00:00:00Z");
        ReleaseFreshness freshness = new ReleaseFreshness(CLOCK);

        freshness.accept(current);

        assertThat(freshness.highestSeen("game-x")).hasValue(17);
    }

    @Test
    void theSameSequenceMayBeAcceptedAgain() throws Exception {
        ReleaseManifest current = signed("game-x", 17, "2026-10-12T00:00:00Z");
        ReleaseFreshness freshness = new ReleaseFreshness(CLOCK);
        freshness.accept(current);

        assertThatCode(() -> freshness.accept(current)).doesNotThrowAnyException();
        assertThat(freshness.highestSeen("game-x")).hasValue(17);
    }

    @Test
    void aHigherSequenceRaisesTheWatermark() throws Exception {
        ReleaseFreshness freshness = new ReleaseFreshness(CLOCK);
        freshness.accept(signed("game-x", 17, "2026-10-12T00:00:00Z"));

        freshness.accept(signed("game-x", 18, "2026-10-12T00:00:00Z"));

        assertThat(freshness.highestSeen("game-x")).hasValue(18);
    }

    @Test
    void aLowerSequenceIsAnExplicitRollback() throws Exception {
        ReleaseFreshness freshness = new ReleaseFreshness(CLOCK);
        freshness.accept(signed("game-x", 17, "2026-10-12T00:00:00Z"));
        ReleaseManifest older = signed("game-x", 16, "2026-10-12T00:00:00Z");

        assertThatThrownBy(() -> freshness.accept(older))
                .isInstanceOf(StaleReleaseException.class)
                .hasMessageContaining("behind highest-seen 17")
                .hasMessageContaining("game-x");
        StaleReleaseException thrown = caught(freshness, older);
        assertThat(thrown.reason()).isEqualTo(StaleReleaseException.Reason.ROLLBACK);
        assertThat(thrown.sequence()).isEqualTo(16);
        assertThat(freshness.highestSeen("game-x")).hasValue(17);
    }

    @Test
    void anExpiredReleaseIsRefusedAndDoesNotMoveTheWatermark() throws Exception {
        ReleaseFreshness freshness = new ReleaseFreshness(CLOCK);
        ReleaseManifest expired = signed("game-x", 99, "2026-09-12T00:00:00Z");

        assertThatThrownBy(() -> freshness.accept(expired))
                .isInstanceOf(StaleReleaseException.class)
                .hasMessageContaining("expired at 2026-09-12T00:00:00Z");
        StaleReleaseException thrown = caught(freshness, expired);
        assertThat(thrown.reason()).isEqualTo(StaleReleaseException.Reason.EXPIRED);
        assertThat(freshness.highestSeen("game-x")).isEmpty();
    }

    @Test
    void aReleaseThatExpiresExactlyNowIsRefused() throws Exception {
        ReleaseFreshness freshness = new ReleaseFreshness(CLOCK);
        ReleaseManifest onTheDot = signed("game-x", 1, NOW.toString());

        assertThatThrownBy(() -> freshness.accept(onTheDot))
                .isInstanceOf(StaleReleaseException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void productsKeepIndependentHighWaterMarks() throws Exception {
        ReleaseFreshness freshness = new ReleaseFreshness(CLOCK);
        freshness.accept(signed("game-x", 17, "2026-10-12T00:00:00Z"));

        assertThatCode(() -> freshness.accept(signed("game-y", 1, "2026-10-12T00:00:00Z")))
                .doesNotThrowAnyException();
        assertThat(freshness.highestSeen("game-x")).hasValue(17);
        assertThat(freshness.highestSeen("game-y")).hasValue(1);
    }

    @Test
    void ignorePolicyDoesNotLowerTheWatermark() throws Exception {
        ReleaseFreshness freshness = new ReleaseFreshness(CLOCK, ReleaseFreshness.RollbackPolicy.IGNORE);
        freshness.accept(signed("game-x", 17, "2026-10-12T00:00:00Z"));

        assertThatCode(() -> freshness.accept(signed("game-x", 16, "2026-10-12T00:00:00Z")))
                .doesNotThrowAnyException();
        assertThat(freshness.highestSeen("game-x")).hasValue(17);
    }

    @Test
    void signatureVerifyStaysIndependentOfFreshness() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ReleaseManifest expired = ManifestSigner.sign(unsigned("game-x", 1, "2026-09-12T00:00:00Z"),
                keys.getPrivate());

        assertThatCode(() -> ManifestVerifier.verify(expired, keys.getPublic()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new ReleaseFreshness(CLOCK).accept(expired))
                .isInstanceOf(StaleReleaseException.class);
    }

    private StaleReleaseException caught(ReleaseFreshness freshness, ReleaseManifest manifest) {
        try {
            freshness.accept(manifest);
            throw new AssertionError("expected StaleReleaseException");
        } catch (StaleReleaseException e) {
            return e;
        }
    }

    private ReleaseManifest signed(String productId, long sequence, String expiresAt) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return ManifestSigner.sign(unsigned(productId, sequence, expiresAt), keys.getPrivate());
    }

    private ReleaseManifest unsigned(String productId, long sequence, String expiresAt) throws Exception {
        Path file = tempDir.resolve(productId + "-" + sequence + ".bin");
        Files.write(file, new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        List<ChunkEntry> chunks = new FileChunker(4).chunk(file);
        return ReleaseManifestFactory.unsigned(
                productId, "1.4.0", file, 4, chunks,
                "2026-08-12T00:00:00Z", expiresAt, sequence, "release-key-2026-01");
    }
}
