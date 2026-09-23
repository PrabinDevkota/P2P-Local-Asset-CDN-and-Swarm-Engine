package com.prabin.swarmedge.manifest;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fail-closed freshness after signature verify (blueprint P9-01).
 *
 * <p>{@link ManifestVerifier} only checks the Ed25519 stamp. This class is the second
 * gate: an expired {@code expiresAt} is refused, and a {@code sequence} below the
 * highest already accepted for that {@code productId} is a rollback. Crypto and
 * freshness stay separate so a forged expired manifest still fails as a bad
 * signature first, and a valid old stamp cannot be replayed as a current release.
 *
 * <p>Highest-seen is in memory unless a {@link SequenceLedger} is supplied. With a
 * ledger, a restart still refuses a sequence behind the one already accepted.
 */
public final class ReleaseFreshness {

    public enum RollbackPolicy {
        REJECT,
        IGNORE
    }

    private final Clock clock;
    private final RollbackPolicy rollback;
    private final SequenceLedger ledger;
    private final ConcurrentHashMap<String, Long> highest = new ConcurrentHashMap<>();

    public ReleaseFreshness(Clock clock) {
        this(clock, RollbackPolicy.REJECT, null);
    }

    public ReleaseFreshness(Clock clock, RollbackPolicy rollback) {
        this(clock, rollback, null);
    }

    public ReleaseFreshness(Clock clock, RollbackPolicy rollback, SequenceLedger ledger) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rollback = Objects.requireNonNull(rollback, "rollback");
        this.ledger = ledger;
        if (ledger != null) {
            highest.putAll(ledger.snapshot());
        }
    }

    /**
     * Accept this release as current, or throw {@link StaleReleaseException}.
     *
     * <p>Call after {@link ManifestVerifier#verify}. An expired manifest does not
     * update the high-water mark. A same-or-higher sequence does.
     */
    public void accept(ReleaseManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        Instant now = clock.instant();
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(manifest.expiresAt());
        } catch (RuntimeException e) {
            throw new ManifestValidationException("expiresAt is not a UTC instant");
        }
        if (!now.isBefore(expiresAt)) {
            throw new StaleReleaseException(
                    StaleReleaseException.Reason.EXPIRED,
                    manifest.productId(),
                    manifest.sequence(),
                    "release expired at " + manifest.expiresAt() + " (now " + now + ")");
        }
        long sequence = manifest.sequence();
        Long seen = highest.get(manifest.productId());
        if (seen != null && sequence < seen) {
            if (rollback == RollbackPolicy.REJECT) {
                throw new StaleReleaseException(
                        StaleReleaseException.Reason.ROLLBACK,
                        manifest.productId(),
                        sequence,
                        "release sequence " + sequence + " is behind highest-seen " + seen
                                + " for product " + manifest.productId());
            }
            return;
        }
        if (ledger != null && (seen == null || sequence > seen)) {
            try {
                ledger.raise(manifest.productId(), sequence);
            } catch (java.io.IOException e) {
                throw new ManifestValidationException("could not record highest-seen sequence");
            }
        }
        highest.merge(manifest.productId(), sequence, Math::max);
    }

    public OptionalLong highestSeen(String productId) {
        Long seen = highest.get(Objects.requireNonNull(productId, "productId"));
        return seen == null ? OptionalLong.empty() : OptionalLong.of(seen);
    }
}
