package com.prabin.tracker.rank;

import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;
import com.prabin.swarmedge.common.locality.LocalityClass;
import com.prabin.tracker.store.PeerRecord;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Rank candidates by locality class (blueprint P3-03, P6-01). Closest first, self never
 * returned, and never an IP {@code /24}.
 *
 * <p>Closeness comes from {@link Locality}, which the peer agent scores with too. That
 * sharing is the point of P6-01: while the tracker had its own notion of closeness, it
 * ordered by site before network group, so a peer elsewhere in the building ranked level
 * with one on the same switch and the finest distinction available was thrown away.
 *
 * <p>Ranking is a hint about where to look. It is not a permission and not a statement
 * about the bytes: a candidate at the top of this list is still an untrusted source
 * whose chunks have to match the signed manifest.
 */
public final class LocalityRanker {

    private LocalityRanker() {
    }

    public static List<PeerRecord> rank(
            List<PeerRecord> peers,
            String siteId,
            String networkGroupId,
            PeerId self,
            int limit
    ) {
        return rank(peers, new Locality(siteId, networkGroupId), self, limit);
    }

    /**
     * @param asker the requesting peer's own labels, taken from its issued token rather
     *              than from anything it asserts in the query
     */
    public static List<PeerRecord> rank(List<PeerRecord> peers, Locality asker, PeerId self, int limit) {
        Objects.requireNonNull(peers, "peers");
        Objects.requireNonNull(asker, "asker");
        Objects.requireNonNull(self, "self");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        // peerId breaks ties so the same swarm state always produces the same list: a
        // discovery latency measurement is only meaningful if the answer is stable.
        Comparator<PeerRecord> byClosenessThenId = Comparator
                .comparing((PeerRecord peer) -> classOf(asker, peer))
                .thenComparing(PeerRecord::peerId);
        return peers.stream()
                .filter(peer -> !self.equals(peer.peerId()))
                .sorted(byClosenessThenId)
                .limit(limit)
                .toList();
    }

    static LocalityClass classOf(Locality asker, PeerRecord peer) {
        return asker.classify(peer.locality());
    }
}
