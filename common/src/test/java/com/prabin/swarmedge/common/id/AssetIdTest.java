package com.prabin.swarmedge.common.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetIdTest {

    @Test
    void hexRoundTrip() {
        String hex = "a".repeat(64);
        AssetId id = AssetId.fromHex(hex);
        assertThat(id.toHex()).isEqualTo(hex);
        assertThat(AssetId.fromHex(hex.toUpperCase())).isEqualTo(id);
    }

    @Test
    void rejectsWrongLength() {
        assertThatThrownBy(() -> AssetId.fromHex("aa")).isInstanceOf(IllegalArgumentException.class);
    }
}
