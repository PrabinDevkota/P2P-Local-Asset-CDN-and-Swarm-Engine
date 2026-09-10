package com.prabin.tracker.store;

import com.prabin.swarmedge.common.id.PeerId;

import java.util.Objects;

/** Compact peer state stored in Redis. No file bytes. */
public record PeerRecord(
        PeerId peerId,
        String ip,
        int port,
        String siteId,
        String networkGroupId,
        int bitCount,
        byte[] bits
) {
    public PeerRecord {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(networkGroupId, "networkGroupId");
        Objects.requireNonNull(bits, "bits");
        bits = bits.clone();
    }

    @Override
    public byte[] bits() {
        return bits.clone();
    }
}
