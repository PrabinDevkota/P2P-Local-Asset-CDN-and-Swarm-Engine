package com.prabin.tracker.rank;

import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.tracker.store.PeerRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalityRankerTest {

    private static final PeerId SELF = peer("11");
    private static final PeerId SAME_SITE = peer("22");
    private static final PeerId SAME_GROUP = peer("33");
    private static final PeerId REMOTE = peer("44");
    private static final PeerId OTHER_SITE = peer("55");

    @Test
    void ranksSiteThenNetworkGroupExcludesSelfAndCapsLimit() {
        PeerRecord self = record(SELF, "site-a", "ng-1");
        PeerRecord site = record(SAME_SITE, "site-a", "ng-9");
        PeerRecord group = record(SAME_GROUP, "site-b", "ng-1");
        PeerRecord remote = record(REMOTE, "site-c", "ng-2");
        PeerRecord otherSite = record(OTHER_SITE, "site-a", "ng-1");

        List<PeerRecord> ranked = LocalityRanker.rank(
                List.of(remote, self, group, site, otherSite),
                "site-a",
                "ng-1",
                SELF,
                3);

        assertThat(ranked).extracting(p -> p.peerId()).containsExactly(SAME_SITE, OTHER_SITE, SAME_GROUP);
        assertThat(ranked).noneMatch(p -> SELF.equals(p.peerId()));
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> LocalityRanker.rank(List.of(), "s", "g", SELF, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    private static PeerRecord record(PeerId id, String site, String group) {
        return new PeerRecord(id, "10.0.0.1", 9091, site, group, 0, new byte[0]);
    }

    private static PeerId peer(String twoHex) {
        return PeerId.fromHex(twoHex.repeat(16));
    }
}
