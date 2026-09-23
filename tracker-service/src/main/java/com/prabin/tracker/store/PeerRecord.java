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
        byte[] bits,
        int capabilities,
        long uploadBudgetBytesPerSecond,
        java.util.OptionalDouble uploadLoad
) {
    public PeerRecord(PeerId peerId, String ip, int port, String siteId, String networkGroupId,
                      int bitCount, byte[] bits) {
        this(peerId, ip, port, siteId, networkGroupId, bitCount, bits, 0, 0L, java.util.OptionalDouble.empty());
    }

    public PeerRecord {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(networkGroupId, "networkGroupId");
        Objects.requireNonNull(bits, "bits");
        Objects.requireNonNull(uploadLoad, "uploadLoad");
        bits = bits.clone();
        if (capabilities < 0) {
            throw new IllegalArgumentException("capabilities must be non-negative");
        }
        if (uploadBudgetBytesPerSecond < 0) {
            throw new IllegalArgumentException("uploadBudget must be non-negative");
        }
        if (uploadLoad.isPresent()) {
            double load = uploadLoad.getAsDouble();
            if (load < 0 || load > 1) {
                throw new IllegalArgumentException("uploadLoad must be in 0..1");
            }
        }
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
