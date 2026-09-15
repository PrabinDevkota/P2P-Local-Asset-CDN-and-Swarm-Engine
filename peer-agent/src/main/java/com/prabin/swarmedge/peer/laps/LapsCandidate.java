package com.prabin.swarmedge.peer.laps;

import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * One peer as LAPS sees it (blueprint P6-03, §8.2).
 *
 * <p>Three kinds of input, and they are not equally trustworthy. {@code locality} is
 * administrative fact that arrives with the peer's token. {@code metrics} is what we
 * observed ourselves. {@code advertisedUploadLoad} is the odd one out: it is the peer's
 * own claim about how busy it is, because how loaded someone else's uplink is cannot be
 * measured from here.
 *
 * <p>That claim is why §8.2 gives capacity the smallest weight of the five. A peer can
 * lie about being idle and buy itself a little ranking, and the cap on what that is
 * worth is the defence. It buys ranking only — a chunk from a peer that lied still has
 * to match the signed manifest, and verification does not consult the score.
 *
 * @param advertisedUploadLoad normalized 0..1 busy-ness, or empty when the peer said nothing
 */
public record LapsCandidate(
        PeerId peerId,
        Locality locality,
        PeerMetrics metrics,
        OptionalDouble advertisedUploadLoad) {

    public LapsCandidate {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(locality, "locality");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(advertisedUploadLoad, "advertisedUploadLoad");
        if (advertisedUploadLoad.isPresent()) {
            double load = advertisedUploadLoad.getAsDouble();
            if (!Double.isFinite(load) || load < 0 || load > 1) {
                throw new IllegalArgumentException(
                        "advertisedUploadLoad must be in 0..1, got " + load);
            }
        }
    }

    /** A peer that has told us nothing about its load, which is the usual case. */
    public static LapsCandidate of(PeerId peerId, Locality locality, PeerMetrics metrics) {
        return new LapsCandidate(peerId, locality, metrics, OptionalDouble.empty());
    }
}
