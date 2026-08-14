package com.prabin.swarmedge.protocol;

import java.util.Arrays;
import java.util.Objects;

public final class PeerFrame {

    private final byte version;
    private final MessageType type;
    private final int flags;
    private final long requestId;
    private final byte[] payload;

    public PeerFrame(byte version, MessageType type, int flags, long requestId, byte[] payload) {
        this.version = version;
        this.type = Objects.requireNonNull(type, "type");
        this.flags = flags;
        this.requestId = requestId;
        this.payload = payload == null ? new byte[0] : payload.clone();
    }

    public byte version() {
        return version;
    }

    public MessageType type() {
        return type;
    }

    public int flags() {
        return flags;
    }

    public long requestId() {
        return requestId;
    }

    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PeerFrame other
                && version == other.version
                && type == other.type
                && flags == other.flags
                && requestId == other.requestId
                && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, type, flags, requestId, Arrays.hashCode(payload));
    }
}
