package com.prabin.swarmedge.common.id;

import com.prabin.swarmedge.common.Defaults;

import java.util.Arrays;
import java.util.Objects;

/** 32-byte SHA-256 identity of the canonical unsigned manifest core. */
public final class AssetId {

    private final byte[] bytes;

    private AssetId(byte[] bytes) {
        this.bytes = bytes;
    }

    public static AssetId of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != Defaults.ASSET_ID_LENGTH) {
            throw new IllegalArgumentException("assetId must be " + Defaults.ASSET_ID_LENGTH + " bytes");
        }
        return new AssetId(bytes.clone());
    }

    public static AssetId fromHex(String hex) {
        return of(Hex.fromHex(hex));
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String toHex() {
        return Hex.toLowerHex(bytes);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AssetId other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return toHex();
    }
}
