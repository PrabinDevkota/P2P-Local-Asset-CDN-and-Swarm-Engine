package com.prabin.swarmedge.peer.laps;

import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The source policy that separates the three baselines: a swarm dials the head of its
 * candidate list, so the order of that list is the policy.
 */
class PeerSelectorTest {

    private static final int BLOCK = 256 * 1024;
    private static final long SEED = 20260915L;
    private static final Locality ME = new Locality("hq", "floor-2");

    @Test
    void localityOnlyDialsTheNearestPeerFirst() {
        // B2: nothing but distance, which is the comparison B3 has to beat.
        PeerSelector selector = new PeerSelector(LapsWeights.localityOnly(), ME, SEED);
        Candidates candidates = new Candidates();
        var remote = candidates.add(1, "branch", "wifi");
        var sameSite = candidates.add(2, "hq", "floor-9");
        var sameSwitch = candidates.add(3, "hq", "floor-2");

        assertThat(selector.rank(candidates.shuffledIsh()))
                .containsExactly(sameSwitch, sameSite, remote);
    }

    @Test
    void lapsWillPreferADistantPeerWhenTheNearOneHasProvedItself() {
        // Not an assertion that this is better; only that measured terms can outweigh
        // locality, which is exactly what B2 against B3 is meant to measure.
        PeerSelector selector = new PeerSelector(new LapsWeights(0.2, 0.8, 0.0, 0.0, 0.0), ME, SEED);
        Candidates candidates = new Candidates();
        var nearAndSlow = candidates.add(1, "hq", "floor-2");
        var farAndFast = candidates.add(2, "branch", "wifi");

        selector.metricsFor(nearAndSlow.peerId()).blockCompleted(BLOCK, Duration.ofSeconds(2));
        selector.metricsFor(farAndFast.peerId()).blockCompleted(BLOCK, Duration.ofMillis(20));

        assertThat(selector.rank(candidates.all())).containsExactly(farAndFast, nearAndSlow);
    }

    @Test
    void whatWeLearnedAboutAPeerOutlivesTheSessionThatLearnedIt() {
        // The whole value of the measured terms. If history died with the session, every
        // round would re-rank from scratch and a slow peer would keep being chosen.
        PeerSelector selector = new PeerSelector(LapsWeights.defaults(), ME, SEED);
        Candidates candidates = new Candidates();
        var first = candidates.add(1, "hq", "floor-2");
        var second = candidates.add(2, "hq", "floor-2");

        selector.metricsFor(first.peerId()).blockCompleted(BLOCK, Duration.ofSeconds(3));
        selector.metricsFor(second.peerId()).blockCompleted(BLOCK, Duration.ofMillis(10));

        assertThat(selector.rank(candidates.all())).containsExactly(second, first);
        // Same answer on the next round: nothing new was observed.
        assertThat(selector.rank(candidates.all())).containsExactly(second, first);
    }

    @Test
    void aPeerKeepsItsHistoryWhenItComesBackOnAnotherPort() {
        PeerSelector selector = new PeerSelector(LapsWeights.defaults(), ME, SEED);
        PeerId reconnecting = peerId(1);
        selector.metricsFor(reconnecting).blockFailed();
        selector.metricsFor(reconnecting).blockFailed();

        PeerSelector.Candidate onANewPort = PeerSelector.Candidate.of(
                new InetSocketAddress("10.0.0.1", 40001), reconnecting, ME);
        PeerSelector.Candidate fresh = PeerSelector.Candidate.of(
                new InetSocketAddress("10.0.0.2", 9091), peerId(2), ME);

        // Keyed by peer, not by address, so changing port is not a clean slate.
        assertThat(selector.rank(List.of(onANewPort, fresh))).containsExactly(fresh, onANewPort);
        assertThat(selector.metricsFor(reconnecting).blocksFailed()).isEqualTo(2);
    }

    @Test
    void anUnknownPeerStartsWithAnEmptyRecordRatherThanABadOne() {
        PeerSelector selector = new PeerSelector(LapsWeights.defaults(), ME, SEED);

        PeerMetrics fresh = selector.metricsFor(peerId(7));

        assertThat(fresh.hasSamples()).isFalse();
        assertThat(fresh.goodputBytesPerSecond()).isEmpty();
        // Same object next time, so a session can record into what selection will read.
        assertThat(selector.metricsFor(peerId(7))).isSameAs(fresh);
    }

    @Test
    void theDialOrderIsJustTheAddressesInRankedOrder() {
        PeerSelector selector = new PeerSelector(LapsWeights.localityOnly(), ME, SEED);
        Candidates candidates = new Candidates();
        var remote = candidates.add(1, "branch", "wifi");
        var near = candidates.add(2, "hq", "floor-2");

        assertThat(selector.dialOrder(candidates.all()))
                .containsExactly(near.address(), remote.address());
    }

    @Test
    void aRankingComesBackWithItsReasoning() {
        PeerSelector selector = new PeerSelector(LapsWeights.defaults(), ME, SEED);
        Candidates candidates = new Candidates();
        candidates.add(1, "hq", "floor-2");
        candidates.add(2, "branch", "wifi");

        List<LapsScorer.Scored> explained = selector.explain(candidates.all());

        assertThat(explained).hasSize(2);
        assertThat(explained.getFirst().terms().locality()).isEqualTo(1.0);
        assertThat(explained.getFirst().score()).isGreaterThan(explained.getLast().score());
    }

    @Test
    void anEmptyCandidateListRanksToNothing() {
        PeerSelector selector = new PeerSelector(LapsWeights.defaults(), ME, SEED);

        assertThat(selector.rank(List.of())).isEmpty();
        assertThat(selector.dialOrder(List.of())).isEmpty();
    }

    @Test
    void aHealthySameSiteEdgeIsDialledBeforeADesktopSeeder() {
        PeerSelector selector = new PeerSelector(LapsWeights.localityOnly(), ME, SEED);
        PeerSelector.Candidate desktop = PeerSelector.Candidate.of(
                new InetSocketAddress("10.0.0.1", 9091), peerId(1), ME);
        PeerSelector.Candidate edge = PeerSelector.Candidate.edge(
                new InetSocketAddress("10.0.0.2", 9091), peerId(2), new Locality("hq", "floor-9"));

        assertThat(selector.rank(List.of(desktop, edge))).containsExactly(edge, desktop);
    }

    @Test
    void anOverloadedEdgeIsNotPinnedFirst() {
        PeerSelector selector = new PeerSelector(LapsWeights.localityOnly(), ME, SEED);
        PeerSelector.Candidate desktop = PeerSelector.Candidate.of(
                new InetSocketAddress("10.0.0.1", 9091), peerId(1), ME);
        PeerSelector.Candidate edge = PeerSelector.Candidate.edge(
                new InetSocketAddress("10.0.0.2", 9091), peerId(2), new Locality("hq", "floor-9"))
                .withAdvertisedUploadLoad(0.95);

        assertThat(selector.rank(List.of(desktop, edge))).containsExactly(desktop, edge);
    }

    @Test
    void aSickEdgeFallsBackToOrdinaryLapsOrder() {
        PeerSelector selector = new PeerSelector(LapsWeights.defaults(), ME, SEED);
        PeerSelector.Candidate desktop = PeerSelector.Candidate.of(
                new InetSocketAddress("10.0.0.1", 9091), peerId(1), ME);
        PeerSelector.Candidate edge = PeerSelector.Candidate.edge(
                new InetSocketAddress("10.0.0.2", 9091), peerId(2), ME);
        selector.metricsFor(edge.peerId()).blockFailed();
        selector.metricsFor(edge.peerId()).blockFailed();

        assertThat(selector.rank(List.of(desktop, edge))).containsExactly(desktop, edge);
    }

    @Test
    void aRemoteEdgeIsNotPreferredOverALocalPeer() {
        PeerSelector selector = new PeerSelector(LapsWeights.localityOnly(), ME, SEED);
        PeerSelector.Candidate local = PeerSelector.Candidate.of(
                new InetSocketAddress("10.0.0.1", 9091), peerId(1), ME);
        PeerSelector.Candidate remoteEdge = PeerSelector.Candidate.edge(
                new InetSocketAddress("10.0.0.2", 9091), peerId(2), new Locality("branch", "wifi"));

        assertThat(selector.rank(List.of(remoteEdge, local))).containsExactly(local, remoteEdge);
    }

    @Test
    void theSamePeerAtTwoAddressesIsRejected() {
        PeerSelector selector = new PeerSelector(LapsWeights.defaults(), ME, SEED);
        PeerId duplicate = peerId(1);

        assertThatThrownBy(() -> selector.rank(List.of(
                PeerSelector.Candidate.of(new InetSocketAddress("10.0.0.1", 9091), duplicate, ME),
                PeerSelector.Candidate.of(new InetSocketAddress("10.0.0.2", 9091), duplicate, ME))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appears twice");
    }

    /** Builds candidates with distinct peer ids and addresses. */
    private static final class Candidates {

        private final List<PeerSelector.Candidate> candidates = new java.util.ArrayList<>();

        PeerSelector.Candidate add(int id, String site, String group) {
            PeerSelector.Candidate candidate = PeerSelector.Candidate.of(
                    new InetSocketAddress("10.0.0." + id, 9091), peerId(id), new Locality(site, group));
            candidates.add(candidate);
            return candidate;
        }

        List<PeerSelector.Candidate> all() {
            return List.copyOf(candidates);
        }

        /** Reversed, to prove the input order does not survive into the output. */
        List<PeerSelector.Candidate> shuffledIsh() {
            return all().reversed();
        }
    }

    private static PeerId peerId(int id) {
        byte[] bits = new byte[16];
        Arrays.fill(bits, (byte) id);
        return PeerId.of(bits);
    }
}
