package com.prabin.swarmedge.peer.laps;

import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;

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
 * the order of that list <em>is</em> the source policy. This turns a flat list from the
 * tracker into a ranked one, and it is the only place the three baselines differ:
 *
 * <ul>
 *   <li><b>B1</b> — no selector at all; dial in whatever order discovery returned.</li>
 *   <li><b>B2</b> — {@link LapsWeights#localityOnly()}: nearest first and nothing else.</li>
 *   <li><b>B3</b> — {@link LapsWeights#defaults()}: the full LAPS score.</li>
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

    private final LapsScorer scorer;
    private final Map<PeerId, PeerMetrics> history = new HashMap<>();

    public PeerSelector(LapsWeights weights, Locality self, long tieBreakSeed) {
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
        List<Candidate> ranked = new ArrayList<>(candidates.size());
        for (LapsScorer.Scored entry : scorer.rank(scored)) {
            ranked.add(byPeer.get(entry.candidate().peerId()));
        }
        return List.copyOf(ranked);
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
            OptionalDouble advertisedUploadLoad) {

        public Candidate {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(peerId, "peerId");
            Objects.requireNonNull(locality, "locality");
            Objects.requireNonNull(advertisedUploadLoad, "advertisedUploadLoad");
        }

        public static Candidate of(InetSocketAddress address, PeerId peerId, Locality locality) {
            return new Candidate(address, peerId, locality, OptionalDouble.empty());
        }
    }
}
