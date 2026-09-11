package com.prabin.swarmedge.common.id;

import com.prabin.swarmedge.common.Defaults;

import java.util.Arrays;
import java.util.Objects;

/** 16-byte peer identity bound to an auth token in later phases. */
public final class PeerId implements Comparable<PeerId> {

    private final byte[] bytes;

    private PeerId(byte[] bytes) {
        this.bytes = bytes;
    }

    public static PeerId of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != Defaults.PEER_ID_LENGTH) {
            throw new IllegalArgumentException("peerId must be " + Defaults.PEER_ID_LENGTH + " bytes");
        }
        return new PeerId(bytes.clone());
    }

    public static PeerId fromHex(String hex) {
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
        return o instanceof PeerId other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public int compareTo(PeerId other) {
        return Arrays.compareUnsigned(bytes, Objects.requireNonNull(other, "other").bytes);
    }

    @Override
    public String toString() {
        return toHex();
    }
}
