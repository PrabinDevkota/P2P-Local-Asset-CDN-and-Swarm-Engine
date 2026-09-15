package com.prabin.swarmedge.common.locality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-01 acceptance: a locality class comes out of administrative labels, never out of
 * an address.
 */
class LocalityTest {

    private static final Locality HQ_FLOOR_2 = new Locality("hq", "floor-2");

    @Test
    void thePeerOnTheSameSwitchIsTheCloseOne() {
        assertThat(HQ_FLOOR_2.classify(new Locality("hq", "floor-2")))
                .isEqualTo(LocalityClass.SAME_NETWORK_GROUP);
    }

    @Test
    void theSameSiteThroughADifferentGroupIsStillLocalButNotAsClose() {
        LocalityClass sameSite = HQ_FLOOR_2.classify(new Locality("hq", "floor-5"));

        assertThat(sameSite).isEqualTo(LocalityClass.SAME_SITE);
        assertThat(sameSite.isLocal()).isTrue();
        assertThat(sameSite.score()).isLessThan(LocalityClass.SAME_NETWORK_GROUP.score());
    }

    @Test
    void anotherSiteIsRemoteNoMatterWhatTheGroupIsCalled() {
        // "floor-2" exists in every building. A shared group label across sites is a
        // naming coincidence, and treating it as proximity would send traffic over the
        // WAN link this project exists to spare.
        LocalityClass remote = HQ_FLOOR_2.classify(new Locality("branch", "floor-2"));

        assertThat(remote).isEqualTo(LocalityClass.REMOTE_SITE);
        assertThat(remote.isLocal()).isFalse();
    }

    @Test
    void closenessIsSymmetric() {
        Locality far = new Locality("branch", "wifi");

        assertThat(HQ_FLOOR_2.classify(far)).isEqualTo(far.classify(HQ_FLOOR_2));
    }

    @Test
    void theEnumOrderIsThePreferenceOrder() {
        // Declared best-first, so ranking needs no comparator and cannot drift out of
        // step with the scores.
        assertThat(List.of(LocalityClass.values()))
                .containsExactly(LocalityClass.SAME_NETWORK_GROUP,
                        LocalityClass.SAME_SITE,
                        LocalityClass.REMOTE_SITE);
        assertThat(LocalityClass.SAME_NETWORK_GROUP).isLessThan(LocalityClass.SAME_SITE);
        assertThat(LocalityClass.SAME_SITE).isLessThan(LocalityClass.REMOTE_SITE);
    }

    @Test
    void theScoresAreTheBlueprintsOwnDefaults() {
        assertThat(LocalityClass.SAME_NETWORK_GROUP.score()).isEqualTo(1.0);
        assertThat(LocalityClass.SAME_SITE.score()).isEqualTo(0.7);
        assertThat(LocalityClass.REMOTE_SITE.score()).isEqualTo(0.2);
        assertThat(HQ_FLOOR_2.scoreOf(new Locality("hq", "floor-2"))).isEqualTo(1.0);
    }

    @Test
    void labelsAreComparedExactlyAfterTrimmingTheirEdges() {
        assertThat(new Locality("  hq  ", " floor-2 ")).isEqualTo(HQ_FLOOR_2);
        // No case folding: these are opaque identifiers, and guessing that "HQ" and "hq"
        // are one site is the kind of inference an address-based scheme would make.
        assertThat(HQ_FLOOR_2.classify(new Locality("HQ", "floor-2")))
                .isEqualTo(LocalityClass.REMOTE_SITE);
    }

    @Test
    void aBlankLabelIsRejectedRatherThanTreatedAsAWildcard() {
        assertThatThrownBy(() -> new Locality("", "floor-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("siteId");
        assertThatThrownBy(() -> new Locality("hq", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("networkGroupId");
        assertThatThrownBy(() -> new Locality("hq", null))
                .isInstanceOf(NullPointerException.class);
    }
}
