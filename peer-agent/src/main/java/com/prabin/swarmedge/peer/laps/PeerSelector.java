package com.prabin.swarmedge.peer.laps;

import com.prabin.swarmedge.common.PeerRole;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;
import com.prabin.swarmedge.common.locality.LocalityClass;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Decides what order to dial candidates in (blueprint P6-01, P6-03, §8.1 decision B).
 *
 * <p>A swarm dials the head of its candidate list and keeps the rest as replacements, so
 * the order of that list <em>is</em> the source policy at connect time. Live sessions
 * also record into the {@link PeerMetrics} held here, and the scheduler reads those
 * scores again when leasing a block, so B3 can differ from B2 while everyone is
 * already connected. The three baselines differ only in whether they use this
 * selector, and with which weights:
 *
 * <ul>
 *   <li><b>B1</b> — no selector at all; dial in whatever order discovery returned.</li>
 *   <li><b>B2</b> — {@link LapsWeights#localityOnly()}: nearest first and nothing else.</li>
 *   <li><b>B3</b> — {@link LapsWeights#defaults()}: the full LAPS score.</li>
 *   <li><b>B6</b> — the same LAPS list, except a healthy same-site {@link PeerRole#EDGE}
 *       is pulled to the front. An overloaded or sick EDGE is not pinned.</li>
 * </ul>
 *
 * <p>Metrics are kept here and survive between rounds of selection, which is what makes
 * the measured terms worth anything: a peer that was slow last time is still known to be
 * slow when it comes up as a candidate again. They are keyed by {@link PeerId}, not by
 * address, so a peer that reconnects from a new port keeps its history.
 *
 * <p>Ranking is a hint about where to look first. It grants nothing: every byte from the
 * best-scoring peer in the swarm is still hashed against the signed manifest, and a peer
 * that ranks last is refused nothing except priority.
 */
public final class PeerSelector {

    /** Advertised load at or above this is a saturated EDGE, not a preferred one. */
    static final double EDGE_LOAD_SATURATED = 0.85;
    /** Observed health below this is a sick EDGE; empty health is unproven, not sick. */
    static final double EDGE_HEALTH_FLOOR = 0.4;

    private final LapsScorer scorer;
    private final Locality self;
    private final Map<PeerId, PeerMetrics> history = new HashMap<>();

    public PeerSelector(LapsWeights weights, Locality self, long tieBreakSeed) {
        this.self = Objects.requireNonNull(self, "self");
        this.scorer = new LapsScorer(weights, self, tieBreakSeed);
    }

    /**
     * What we have measured about a peer, created empty on first sight.
     *
     * <p>Handed out rather than copied, so a live session can record into the same object
     * the next selection round will read.
     */
    public synchronized PeerMetrics metricsFor(PeerId peerId) {
        return history.computeIfAbsent(Objects.requireNonNull(peerId, "peerId"),
                key -> new PeerMetrics());
    }

    /** Best first. Same candidates and same history always give the same order. */
    public synchronized List<Candidate> rank(List<Candidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        Map<PeerId, Candidate> byPeer = new HashMap<>();
        List<LapsCandidate> scored = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (byPeer.put(candidate.peerId(), candidate) != null) {
                throw new IllegalArgumentException("the same peer appears twice: " + candidate.peerId());
            }
            scored.add(new LapsCandidate(candidate.peerId(), candidate.locality(),
                    metricsFor(candidate.peerId()), candidate.advertisedUploadLoad()));
        }
        List<Candidate> lapsOrder = new ArrayList<>(candidates.size());
        for (LapsScorer.Scored entry : scorer.rank(scored)) {
            lapsOrder.add(byPeer.get(entry.candidate().peerId()));
        }
        return List.copyOf(preferHealthyEdge(lapsOrder));
    }

    /**
     * B6 two-tier sort: healthy same-site EDGE first, in their LAPS order, then
     * everyone else in LAPS order. Candidates without a role are unchanged, so B2
     * and B3 keep the list they already had.
     */
    private List<Candidate> preferHealthyEdge(List<Candidate> lapsOrder) {
        List<Candidate> edge = new ArrayList<>();
        List<Candidate> rest = new ArrayList<>();
        for (Candidate candidate : lapsOrder) {
            if (healthySameSiteEdge(candidate)) {
                edge.add(candidate);
            } else {
                rest.add(candidate);
            }
        }
        if (edge.isEmpty()) {
            return lapsOrder;
        }
        List<Candidate> ranked = new ArrayList<>(lapsOrder.size());
        ranked.addAll(edge);
        ranked.addAll(rest);
        return ranked;
    }

    private boolean healthySameSiteEdge(Candidate candidate) {
        if (candidate.role() != PeerRole.EDGE) {
            return false;
        }
        if (self.classify(candidate.locality()) == LocalityClass.REMOTE_SITE) {
            return false;
        }
        if (candidate.advertisedUploadLoad().isPresent()
                && candidate.advertisedUploadLoad().getAsDouble() >= EDGE_LOAD_SATURATED) {
            return false;
        }
        var health = metricsFor(candidate.peerId()).healthScore();
        return health.isEmpty() || health.getAsDouble() >= EDGE_HEALTH_FLOOR;
    }

    /** Just the addresses, which is what a swarm downloader takes. */
    public synchronized List<InetSocketAddress> dialOrder(List<Candidate> candidates) {
        List<InetSocketAddress> order = new ArrayList<>(candidates.size());
        for (Candidate candidate : rank(candidates)) {
            order.add(candidate.address());
        }
        return List.copyOf(order);
    }

    /** The ranking with its reasoning attached, for a run record or a log line. */
    public synchronized List<LapsScorer.Scored> explain(List<Candidate> candidates) {
        List<LapsCandidate> scored = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            scored.add(new LapsCandidate(candidate.peerId(), candidate.locality(),
                    metricsFor(candidate.peerId()), candidate.advertisedUploadLoad()));
        }
        return scorer.rank(scored);
    }

    /**
     * One candidate as discovery describes it: where it is, who it is, and the locality
     * labels the tracker vouched for.
     */
    public record Candidate(
            InetSocketAddress address,
            PeerId peerId,
            Locality locality,
            OptionalDouble advertisedUploadLoad,
            PeerRole role) {

        public Candidate {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(peerId, "peerId");
            Objects.requireNonNull(locality, "locality");
            Objects.requireNonNull(advertisedUploadLoad, "advertisedUploadLoad");
        }

        public static Candidate of(InetSocketAddress address, PeerId peerId, Locality locality) {
            return new Candidate(address, peerId, locality, OptionalDouble.empty(), null);
        }

        public static Candidate edge(InetSocketAddress address, PeerId peerId, Locality locality) {
            return new Candidate(address, peerId, locality, OptionalDouble.empty(), PeerRole.EDGE);
        }

        public Candidate withAdvertisedUploadLoad(double load) {
            return new Candidate(address, peerId, locality, OptionalDouble.of(load), role);
        }
    }
}
