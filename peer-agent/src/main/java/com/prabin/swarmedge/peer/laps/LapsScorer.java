package com.prabin.swarmedge.peer.laps;

import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.function.Function;

/**
 * The Locality-Aware Performance Scheduler's source decision (blueprint P6-03, §8.2).
 *
 * <p>Answers decision B — <em>which peer do we ask</em> — where rarest-first answers
 * decision A. One score per peer, from five terms:
 *
 * <pre>
 * peerScore = wL*locality + wT*throughput + wR*rtt + wC*capacity + wH*health
 * </pre>
 *
 * <p>Deterministic and explainable by design, which is the whole claim being made about
 * it: every ranking comes back with its per-term {@link Terms}, so a run can be argued
 * about afterwards instead of taken on faith. Nothing here is learned or adaptive.
 *
 * <h2>Normalization</h2>
 *
 * <p>Throughput, latency, and capacity are normalized <em>within the candidate set being
 * ranked right now</em>, as §8.2 requires. That is deliberate and it has a consequence
 * worth knowing: a score is a statement about a peer relative to its current
 * alternatives, not an absolute rating. The fastest peer in a slow swarm scores 1.0 on
 * throughput. Nothing else is available, so there is nothing else to say. Health needs
 * no normalization because it is already a ratio.
 *
 * <h2>Missing metrics</h2>
 *
 * <p>A peer we have never used gets {@link #UNMEASURED} for the terms we cannot fill in,
 * rather than zero. Zero would rank a brand-new peer below one already measured as
 * hopeless, and it would never be asked for anything, so it would never acquire the
 * metrics that might clear it — the score would be a self-fulfilling prophecy. Neutral
 * puts it behind the peers that have proved themselves and ahead of the peers that have
 * proved the opposite, which is the honest position.
 *
 * <h2>What this is not</h2>
 *
 * <p>LAPS reorders sources. It never grants trust. The top-scoring peer's bytes are
 * hashed against the signed manifest exactly like everyone else's, and no score exempts
 * anyone from that.
 */
public final class LapsScorer {

    /** The value of a term we have no observation for: neither a reward nor a penalty. */
    public static final double UNMEASURED = 0.5;

    private final LapsWeights weights;
    private final Locality self;
    private final long tieBreakSeed;

    /**
     * @param self         the scoring peer's own labels; locality is relative to this
     * @param tieBreakSeed spreads peers whose scores are identical, reproducibly
     */
    public LapsScorer(LapsWeights weights, Locality self, long tieBreakSeed) {
        this.weights = Objects.requireNonNull(weights, "weights");
        this.self = Objects.requireNonNull(self, "self");
        this.tieBreakSeed = tieBreakSeed;
    }

    /** Best first. Never returns more than it was given, and never reorders on a whim. */
    public List<Scored> rank(List<LapsCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<PeerId, Integer> tieRanks = tieRanks(candidates);
        Range goodput = Range.over(candidates, candidate -> candidate.metrics().goodputBytesPerSecond());
        Range rtt = Range.over(candidates, candidate -> candidate.metrics().rttMillis());
        Range load = Range.over(candidates, LapsCandidate::advertisedUploadLoad);

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (LapsCandidate candidate : candidates) {
            Terms terms = new Terms(
                    self.scoreOf(candidate.locality()),
                    goodput.higherIsBetter(candidate.metrics().goodputBytesPerSecond()),
                    // Inverse: the lowest latency in the set is the best of what we have.
                    rtt.lowerIsBetter(candidate.metrics().rttMillis()),
                    // Capacity is the spare room, so it is the complement of the load.
                    load.lowerIsBetter(candidate.advertisedUploadLoad()),
                    candidate.metrics().healthScore().orElse(UNMEASURED));
            scored.add(new Scored(candidate, terms.weightedBy(weights), terms));
        }

        scored.sort(Comparator
                .comparingDouble((Scored entry) -> -entry.score())
                .thenComparingInt(entry -> tieRanks.get(entry.candidate().peerId())));
        return List.copyOf(scored);
    }

    /** The single best source, or empty when there are no candidates. */
    public Optional<Scored> best(List<LapsCandidate> candidates) {
        List<Scored> ranked = rank(candidates);
        return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.getFirst());
    }

    /**
     * A fixed shuffle over this call's candidates.
     *
     * <p>Ordering equal scores by peer id would look deterministic and be quietly
     * biased: the same peers would win every tie in every run, so a low peer id would
     * behave like a small scheduling advantage and turn up in the results as one. The
     * seed keeps ties arbitrary but replayable.
     */
    private Map<PeerId, Integer> tieRanks(List<LapsCandidate> candidates) {
        List<PeerId> ordered = new ArrayList<>(candidates.size());
        for (LapsCandidate candidate : candidates) {
            ordered.add(candidate.peerId());
        }
        // Sort first, so the shuffle does not depend on the order we were handed.
        ordered.sort(Comparator.comparing(PeerId::toHex));
        Random random = new Random(tieBreakSeed);
        for (int i = ordered.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            PeerId swap = ordered.get(i);
            ordered.set(i, ordered.get(j));
            ordered.set(j, swap);
        }
        Map<PeerId, Integer> ranks = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            ranks.put(ordered.get(i), i);
        }
        if (ranks.size() != candidates.size()) {
            throw new IllegalArgumentException("the same peer appears twice in one candidate set");
        }
        return ranks;
    }

    /**
     * Min-max normalization over whatever the candidate set actually reported.
     *
     * <p>When every candidate reports the same value the range collapses, and everyone
     * scores 1.0: they are all equally the best available, and inventing a spread would
     * be inventing information.
     */
    private record Range(double min, double max, boolean any) {

        static Range over(List<LapsCandidate> candidates, Function<LapsCandidate, OptionalDouble> pick) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            boolean any = false;
            for (LapsCandidate candidate : candidates) {
                OptionalDouble value = pick.apply(candidate);
                if (value.isPresent()) {
                    min = Math.min(min, value.getAsDouble());
                    max = Math.max(max, value.getAsDouble());
                    any = true;
                }
            }
            return new Range(min, max, any);
        }

        double higherIsBetter(OptionalDouble value) {
            if (value.isEmpty() || !any) {
                return UNMEASURED;
            }
            return max - min <= 0 ? 1.0 : (value.getAsDouble() - min) / (max - min);
        }

        double lowerIsBetter(OptionalDouble value) {
            if (value.isEmpty() || !any) {
                return UNMEASURED;
            }
            return max - min <= 0 ? 1.0 : (max - value.getAsDouble()) / (max - min);
        }
    }

    /**
     * The five terms behind one score, each in 0..1. Kept so a ranking can be explained
     * rather than asserted.
     */
    public record Terms(double locality, double throughput, double rtt, double capacity, double health) {

        public double weightedBy(LapsWeights weights) {
            return weights.locality() * locality
                    + weights.throughput() * throughput
                    + weights.rtt() * rtt
                    + weights.capacity() * capacity
                    + weights.health() * health;
        }
    }

    public record Scored(LapsCandidate candidate, double score, Terms terms) {
    }
}
