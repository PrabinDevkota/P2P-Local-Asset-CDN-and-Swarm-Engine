package com.prabin.tracker.store;

import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;

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

    /** The two labels as one value, so ranking never re-pairs them by hand. */
    public Locality locality() {
        return new Locality(siteId, networkGroupId);
    }

    @Override
    public byte[] bits() {
        return bits.clone();
    }
}
