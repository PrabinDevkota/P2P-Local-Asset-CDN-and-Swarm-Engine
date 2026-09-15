package com.prabin.swarmedge.peer.laps;

import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-03 acceptance: normalization, missing metrics, tie-breaking, and weight config.
 *
 * <p>Nothing here asserts that LAPS is better than locality-only. That is the experiment
 * (B2 against B3), and a unit test claiming it would be asserting the result.
 */
class LapsScorerTest {

    private static final int BLOCK = 256 * 1024;
    private static final long SEED = 20260915L;
    private static final Locality ME = new Locality("hq", "floor-2");

    @Test
    void theSameSwitchAndTheFastLinkBothCount() {
        // Near but slow against far but fast, under the default weights. Neither term
        // alone decides it, which is the point of having five of them.
        LapsCandidate near = candidate(1, "hq", "floor-2", fast());
        LapsCandidate far = candidate(2, "branch", "floor-2", fast());

        List<LapsScorer.Scored> ranked = scorer(LapsWeights.defaults()).rank(List.of(far, near));

        assertThat(ranked).extracting(entry -> entry.candidate().peerId())
                .containsExactly(near.peerId(), far.peerId());
        assertThat(ranked.getFirst().terms().locality()).isEqualTo(1.0);
        assertThat(ranked.getLast().terms().locality()).isEqualTo(0.2);
    }

    @Test
    void throughputIsNormalizedAcrossTheCandidatesInFrontOfUs() {
        LapsCandidate slow = candidate(1, "hq", "floor-2", metrics(BLOCK, Duration.ofSeconds(1)));
        LapsCandidate middling = candidate(2, "hq", "floor-2", metrics(BLOCK, Duration.ofMillis(500)));
        LapsCandidate quick = candidate(3, "hq", "floor-2", metrics(BLOCK, Duration.ofMillis(100)));

        List<LapsScorer.Scored> ranked =
                scorer(LapsWeights.defaults()).rank(List.of(slow, middling, quick));

        // The best and worst of what is available anchor the scale.
        assertThat(ranked.getFirst().terms().throughput()).isEqualTo(1.0);
        assertThat(ranked.getLast().terms().throughput()).isEqualTo(0.0);
        assertThat(ranked).extracting(entry -> entry.candidate().peerId())
                .containsExactly(quick.peerId(), middling.peerId(), slow.peerId());
    }

    @Test
    void aScoreDescribesAPeerRelativeToItsAlternativesNotInTheAbsolute() {
        // The same peer, ranked twice against different company. Normalizing within the
        // candidate set is what 8.2 asks for, and this is its consequence.
        PeerMetrics theSameSlowLink = metrics(BLOCK, Duration.ofSeconds(1));
        LapsCandidate peer = candidate(1, "hq", "floor-2", theSameSlowLink);

        double aloneInASlowSwarm = scorer(LapsWeights.defaults())
                .rank(List.of(peer)).getFirst().terms().throughput();
        double besideAFasterPeer = scorer(LapsWeights.defaults())
                .rank(List.of(peer, candidate(2, "hq", "floor-2", fast())))
                .stream().filter(entry -> entry.candidate().peerId().equals(peer.peerId()))
                .findFirst().orElseThrow().terms().throughput();

        assertThat(aloneInASlowSwarm).isEqualTo(1.0);
        assertThat(besideAFasterPeer).isEqualTo(0.0);
    }

    @Test
    void lowLatencyScoresHighBecauseTheTermIsInverted() {
        LapsCandidate sluggish = candidate(1, "hq", "floor-2", pinged(Duration.ofMillis(200)));
        LapsCandidate snappy = candidate(2, "hq", "floor-2", pinged(Duration.ofMillis(2)));

        List<LapsScorer.Scored> ranked = scorer(LapsWeights.defaults()).rank(List.of(sluggish, snappy));

        assertThat(ranked.getFirst().candidate().peerId()).isEqualTo(snappy.peerId());
        assertThat(ranked.getFirst().terms().rtt()).isEqualTo(1.0);
        assertThat(ranked.getLast().terms().rtt()).isEqualTo(0.0);
    }

    @Test
    void anIdlePeerOutranksABusyOneOnCapacityAlone() {
        LapsCandidate busy = withLoad(candidate(1, "hq", "floor-2", fast()), 0.9);
        LapsCandidate idle = withLoad(candidate(2, "hq", "floor-2", fast()), 0.1);

        List<LapsScorer.Scored> ranked = scorer(LapsWeights.defaults()).rank(List.of(busy, idle));

        assertThat(ranked.getFirst().candidate().peerId()).isEqualTo(idle.peerId());
        assertThat(ranked.getFirst().terms().capacity()).isEqualTo(1.0);
        assertThat(ranked.getLast().terms().capacity()).isEqualTo(0.0);
    }

    @Test
    void whenEveryPeerReportsTheSameNumberNobodyGetsASpread() {
        // Inventing a difference between identical measurements would be inventing
        // information. They are all equally the best available.
        LapsCandidate first = candidate(1, "hq", "floor-2", fast());
        LapsCandidate second = candidate(2, "hq", "floor-2", fast());

        List<LapsScorer.Scored> ranked = scorer(LapsWeights.defaults()).rank(List.of(first, second));

        assertThat(ranked).allSatisfy(entry -> {
            assertThat(entry.terms().throughput()).isEqualTo(1.0);
            assertThat(entry.terms().rtt()).isEqualTo(1.0);
        });
        assertThat(ranked.getFirst().score()).isEqualTo(ranked.getLast().score());
    }

    @Test
    void anUnmeasuredPeerSitsBetweenTheProvenGoodAndTheProvenBad() {
        // If a fresh peer scored zero it would never be asked for anything, so it would
        // never earn the metrics that might clear it. The score would decide its own
        // evidence.
        LapsCandidate proven = candidate(1, "hq", "floor-2", fast());
        LapsCandidate hopeless = candidate(2, "hq", "floor-2", brokenAndSlow());
        LapsCandidate fresh = candidate(3, "hq", "floor-2", new PeerMetrics());

        List<LapsScorer.Scored> ranked =
                scorer(LapsWeights.defaults()).rank(List.of(hopeless, fresh, proven));

        assertThat(ranked).extracting(entry -> entry.candidate().peerId())
                .containsExactly(proven.peerId(), fresh.peerId(), hopeless.peerId());
        LapsScorer.Scored unproven = ranked.get(1);
        assertThat(unproven.terms().throughput()).isEqualTo(LapsScorer.UNMEASURED);
        assertThat(unproven.terms().rtt()).isEqualTo(LapsScorer.UNMEASURED);
        assertThat(unproven.terms().health()).isEqualTo(LapsScorer.UNMEASURED);
    }

    @Test
    void aSwarmWhereNobodyHasBeenMeasuredFallsBackToLocality() {
        LapsCandidate far = candidate(1, "branch", "wifi", new PeerMetrics());
        LapsCandidate near = candidate(2, "hq", "floor-2", new PeerMetrics());
        LapsCandidate middle = candidate(3, "hq", "floor-9", new PeerMetrics());

        assertThat(scorer(LapsWeights.defaults()).rank(List.of(far, middle, near)))
                .extracting(entry -> entry.candidate().peerId())
                .containsExactly(near.peerId(), middle.peerId(), far.peerId());
    }

    @Test
    void aPeerThatKeepsFailingIsRankedDownWithoutBeingBanned() {
        // Health moves eligibility, never verification: nothing here refuses the peer.
        LapsCandidate reliable = candidate(1, "hq", "floor-2", fast());
        LapsCandidate flaky = candidate(2, "hq", "floor-2", fast());
        for (int i = 0; i < 5; i++) {
            flaky.metrics().blockFailed();
        }

        List<LapsScorer.Scored> ranked = scorer(LapsWeights.defaults()).rank(List.of(flaky, reliable));

        assertThat(ranked.getFirst().candidate().peerId()).isEqualTo(reliable.peerId());
        assertThat(ranked.getLast().terms().health()).isLessThan(0.5);
        assertThat(ranked).hasSize(2);
    }

    @Test
    void identicalPeersAreSpreadReproduciblyRatherThanByPeerId() {
        // Ordering ties by peer id would look deterministic and be quietly biased: the
        // same peers would win every tie in every run, and a low peer id would show up
        // in the results as a scheduling advantage.
        List<LapsCandidate> identical = List.of(
                candidate(1, "hq", "floor-2", fast()),
                candidate(2, "hq", "floor-2", fast()),
                candidate(3, "hq", "floor-2", fast()),
                candidate(4, "hq", "floor-2", fast()),
                candidate(5, "hq", "floor-2", fast()));

        List<PeerId> order = order(scorer(LapsWeights.defaults()).rank(identical));

        assertThat(order).isNotEqualTo(order(scorer(LapsWeights.defaults(), 99L).rank(identical)));
        assertThat(order).isEqualTo(order(scorer(LapsWeights.defaults()).rank(identical)));
        // Argument order must not leak into the result either.
        assertThat(order).isEqualTo(order(scorer(LapsWeights.defaults())
                .rank(identical.reversed())));
    }

    @Test
    void weightsAreConfigurationSoTheSameSwarmCanBeRankedTwoWays() {
        LapsCandidate nearAndSlow = candidate(1, "hq", "floor-2", metrics(BLOCK, Duration.ofSeconds(2)));
        LapsCandidate farAndFast = candidate(2, "branch", "wifi", fast());

        List<LapsCandidate> swarm = List.of(nearAndSlow, farAndFast);

        assertThat(scorer(LapsWeights.localityOnly()).best(swarm).orElseThrow()
                .candidate().peerId()).isEqualTo(nearAndSlow.peerId());
        assertThat(scorer(new LapsWeights(0.0, 1.0, 0.0, 0.0, 0.0)).best(swarm).orElseThrow()
                .candidate().peerId()).isEqualTo(farAndFast.peerId());
    }

    @Test
    void everyScoreStaysInTheRangeTheWeightsPromise() {
        // Weights summing to 1 is what keeps two differently-weighted runs comparable.
        List<LapsCandidate> mixed = List.of(
                withLoad(candidate(1, "hq", "floor-2", fast()), 0.0),
                candidate(2, "branch", "wifi", brokenAndSlow()),
                candidate(3, "hq", "floor-9", new PeerMetrics()));

        assertThat(scorer(LapsWeights.defaults()).rank(mixed))
                .allSatisfy(entry -> assertThat(entry.score()).isBetween(0.0, 1.0));
    }

    @Test
    void theTermsComeBackSoARankingCanBeExplained() {
        LapsScorer.Scored only = scorer(LapsWeights.defaults())
                .rank(List.of(candidate(1, "hq", "floor-2", fast()))).getFirst();

        assertThat(only.terms().weightedBy(LapsWeights.defaults())).isEqualTo(only.score());
        assertThat(only.terms().locality()).isEqualTo(1.0);
    }

    @Test
    void anEmptyCandidateSetIsNotAnError() {
        assertThat(scorer(LapsWeights.defaults()).rank(List.of())).isEmpty();
        assertThat(scorer(LapsWeights.defaults()).best(List.of())).isEmpty();
    }

    @Test
    void theSamePeerTwiceInOneSetIsRejected() {
        LapsCandidate peer = candidate(1, "hq", "floor-2", fast());

        assertThatThrownBy(() -> scorer(LapsWeights.defaults()).rank(List.of(peer, peer)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appears twice");
    }

    @Test
    void weightsThatDoNotSumToOneAreRejected() {
        assertThatThrownBy(() -> new LapsWeights(0.5, 0.5, 0.5, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must sum to 1");
        assertThatThrownBy(() -> new LapsWeights(-0.1, 0.6, 0.2, 0.2, 0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be in 0..1");
        assertThat(LapsWeights.defaults().locality()).isEqualTo(0.35);
    }

    @Test
    void anAdvertisedLoadOutsideItsRangeIsRejected() {
        assertThatThrownBy(() -> new LapsCandidate(peerId(1), ME, new PeerMetrics(),
                OptionalDouble.of(1.5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("advertisedUploadLoad");
    }

    private static List<PeerId> order(List<LapsScorer.Scored> ranked) {
        return ranked.stream().map(entry -> entry.candidate().peerId()).toList();
    }

    private static LapsScorer scorer(LapsWeights weights) {
        return scorer(weights, SEED);
    }

    private static LapsScorer scorer(LapsWeights weights, long seed) {
        return new LapsScorer(weights, ME, seed);
    }

    private static LapsCandidate candidate(int id, String site, String group, PeerMetrics metrics) {
        return LapsCandidate.of(peerId(id), new Locality(site, group), metrics);
    }

    private static LapsCandidate withLoad(LapsCandidate candidate, double load) {
        return new LapsCandidate(candidate.peerId(), candidate.locality(), candidate.metrics(),
                OptionalDouble.of(load));
    }

    private static PeerMetrics metrics(int bytes, Duration elapsed) {
        PeerMetrics metrics = new PeerMetrics();
        metrics.blockCompleted(bytes, elapsed);
        return metrics;
    }

    private static PeerMetrics fast() {
        return metrics(BLOCK, Duration.ofMillis(10));
    }

    private static PeerMetrics brokenAndSlow() {
        PeerMetrics metrics = metrics(BLOCK, Duration.ofSeconds(4));
        metrics.blockFailed();
        metrics.blockFailed();
        metrics.blockFailed();
        return metrics;
    }

    private static PeerMetrics pinged(Duration roundTrip) {
        PeerMetrics metrics = new PeerMetrics();
        metrics.pongObserved(roundTrip);
        return metrics;
    }

    private static PeerId peerId(int id) {
        byte[] bits = new byte[16];
        Arrays.fill(bits, (byte) id);
        return PeerId.of(bits);
    }
}
