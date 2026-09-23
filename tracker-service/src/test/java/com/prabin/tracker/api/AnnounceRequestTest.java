package com.prabin.tracker.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnounceRequestTest {

    private static final String ASSET = "a".repeat(64);
    private static final String PEER = "b".repeat(32);

    @Test
    void acceptsValidAnnounce() {
        AnnounceRequest.Validated v = request(9091, "site-a", "ng-1", 8, "80").validate();

        assertThat(v.assetId().toHex()).isEqualTo(ASSET);
        assertThat(v.peerId().toHex()).isEqualTo(PEER);
        assertThat(v.port()).isEqualTo(9091);
        assertThat(v.siteId()).isEqualTo("site-a");
        assertThat(v.networkGroupId()).isEqualTo("ng-1");
        assertThat(v.bitCount()).isEqualTo(8);
        assertThat(v.bits()).containsExactly((byte) 0x80);
        assertThat(v.capabilities()).isZero();
        assertThat(v.uploadBudgetBytesPerSecond()).isZero();
        assertThat(v.uploadLoad()).isEmpty();
    }

    @Test
    void acceptsCapabilitiesBudgetAndLoad() {
        AnnounceRequest.Validated v = new AnnounceRequest(
                ASSET, PEER, 9091, "site-a", "ng-1", new AnnounceRequest.Bitfield(0, ""),
                4, 1_000_000L, 0.5).validate();

        assertThat(v.capabilities()).isEqualTo(4);
        assertThat(v.uploadBudgetBytesPerSecond()).isEqualTo(1_000_000L);
        assertThat(v.uploadLoad()).hasValue(0.5);
    }

    @Test
    void rejectsALoadOutsideZeroToOne() {
        assertThatThrownBy(() -> new AnnounceRequest(
                ASSET, PEER, 9091, "site-a", "ng-1", new AnnounceRequest.Bitfield(0, ""),
                0, 0L, 1.5).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uploadLoad");
    }

    @Test
    void rejectsBadIdsPortLabelsAndBitfield() {
        assertThatThrownBy(() -> new AnnounceRequest("zz", PEER, 9091, "site-a", "ng-1",
                new AnnounceRequest.Bitfield(0, "")).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assetId");
        assertThatThrownBy(() -> new AnnounceRequest(ASSET, "cc", 9091, "site-a", "ng-1",
                new AnnounceRequest.Bitfield(0, "")).validate())
                .hasMessageContaining("peerId");
        assertThatThrownBy(() -> request(0, "site-a", "ng-1", 0, "").validate())
                .hasMessageContaining("port");
        assertThatThrownBy(() -> request(9091, "site a", "ng-1", 0, "").validate())
                .hasMessageContaining("siteId");
        assertThatThrownBy(() -> request(9091, "site-a", "", 0, "").validate())
                .hasMessageContaining("networkGroupId");
        assertThatThrownBy(() -> request(9091, "site-a", "ng-1", 8, "ff00").validate())
                .hasMessageContaining("bits");
        assertThatThrownBy(() -> request(9091, "site-a", "ng-1", -1, "").validate())
                .hasMessageContaining("bitCount");
        assertThatThrownBy(() -> new AnnounceRequest(ASSET, PEER, 9091, "site-a", "ng-1", null).validate())
                .hasMessageContaining("bitfield");
    }

    @Test
    void emptyBitfieldRequiresEmptyBits() {
        AnnounceRequest.Validated v = request(1, "s", "g", 0, "").validate();
        assertThat(v.bits()).isEmpty();
        assertThatThrownBy(() -> request(1, "s", "g", 0, "00").validate())
                .hasMessageContaining("empty");
    }

    private static AnnounceRequest request(int port, String site, String group, int bitCount, String bits) {
        return new AnnounceRequest(ASSET, PEER, port, site, group, new AnnounceRequest.Bitfield(bitCount, bits));
    }
}
