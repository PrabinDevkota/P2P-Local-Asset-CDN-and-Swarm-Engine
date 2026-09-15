package com.prabin.tracker.rank;

import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;
import com.prabin.swarmedge.common.locality.LocalityClass;
import com.prabin.tracker.store.PeerRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalityRankerTest {

    private static final Locality ASKER = new Locality("site-a", "ng-1");

    private static final PeerId SELF = peer("11");
    private static final PeerId ON_MY_SWITCH = peer("22");
    private static final PeerId ACROSS_THE_BUILDING = peer("33");
    private static final PeerId ANOTHER_SITE = peer("44");

    @Test
    void theClosestPeerComesFirstAndSelfIsNeverReturned() {
        PeerRecord self = record(SELF, "site-a", "ng-1");
        PeerRecord sameGroup = record(ON_MY_SWITCH, "site-a", "ng-1");
        PeerRecord sameSite = record(ACROSS_THE_BUILDING, "site-a", "ng-9");
        PeerRecord remote = record(ANOTHER_SITE, "site-c", "ng-2");

        List<PeerRecord> ranked =
                LocalityRanker.rank(List.of(remote, self, sameSite, sameGroup), ASKER, SELF, 20);

        assertThat(ranked).extracting(PeerRecord::peerId)
                .containsExactly(ON_MY_SWITCH, ACROSS_THE_BUILDING, ANOTHER_SITE);
        assertThat(ranked).noneMatch(peer -> SELF.equals(peer.peerId()));
    }

    @Test
    void aPeerOnTheSameSwitchOutranksOneMerelyInTheSameBuilding() {
        // The distinction this whole ranking exists to make. Checking the site first
        // would let these two tie and then order them by peer id, which is noise.
        PeerRecord sameSite = record(ACROSS_THE_BUILDING, "site-a", "ng-9");
        PeerRecord sameGroup = record(ON_MY_SWITCH, "site-a", "ng-1");

        assertThat(LocalityRanker.rank(List.of(sameSite, sameGroup), ASKER, SELF, 20))
                .extracting(PeerRecord::peerId)
                .containsExactly(ON_MY_SWITCH, ACROSS_THE_BUILDING);
    }

    @Test
    void aMatchingGroupNameInAnotherSiteIsNotProximity() {
        // "ng-1" exists in every building; only the site makes it mean anything.
        PeerRecord elsewhere = record(ANOTHER_SITE, "site-b", "ng-1");

        assertThat(LocalityRanker.classOf(ASKER, elsewhere)).isEqualTo(LocalityClass.REMOTE_SITE);
    }

    @Test
    void theListIsCappedAtTheRequestedLimit() {
        List<PeerRecord> many = List.of(
                record(ON_MY_SWITCH, "site-a", "ng-1"),
                record(ACROSS_THE_BUILDING, "site-a", "ng-9"),
                record(ANOTHER_SITE, "site-c", "ng-2"));

        assertThat(LocalityRanker.rank(many, ASKER, SELF, 2))
                .extracting(PeerRecord::peerId)
                .containsExactly(ON_MY_SWITCH, ACROSS_THE_BUILDING);
    }

    @Test
    void thesameSwarmStateAlwaysProducesTheSameList() {
        // Discovery latency is only worth measuring if the answer does not wobble.
        PeerRecord first = record(ON_MY_SWITCH, "site-a", "ng-1");
        PeerRecord second = record(ACROSS_THE_BUILDING, "site-a", "ng-1");

        assertThat(LocalityRanker.rank(List.of(second, first), ASKER, SELF, 20))
                .isEqualTo(LocalityRanker.rank(List.of(first, second), ASKER, SELF, 20));
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
