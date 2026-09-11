package com.prabin.tracker.rank;

import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.tracker.store.PeerRecord;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Rank candidates by locality labels, not IP /24. Same site first, then same
 * network group, then everyone else. Self is never returned.
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
        Objects.requireNonNull(peers, "peers");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(networkGroupId, "networkGroupId");
        Objects.requireNonNull(self, "self");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Comparator<PeerRecord> byLocalityThenId = Comparator
                .comparingInt((PeerRecord p) -> tier(p, siteId, networkGroupId))
                .thenComparing(PeerRecord::peerId);
        return peers.stream()
                .filter(p -> !self.equals(p.peerId()))
                .sorted(byLocalityThenId)
                .limit(limit)
                .toList();
    }

    static int tier(PeerRecord peer, String siteId, String networkGroupId) {
        if (siteId.equals(peer.siteId())) {
            return 0;
        }
        if (networkGroupId.equals(peer.networkGroupId())) {
            return 1;
        }
        return 2;
    }
}
