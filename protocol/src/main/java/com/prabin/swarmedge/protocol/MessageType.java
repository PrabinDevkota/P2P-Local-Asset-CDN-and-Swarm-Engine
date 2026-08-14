package com.prabin.swarmedge.protocol;

public enum MessageType {
    HELLO((byte) 0x01),
    HELLO_ACK((byte) 0x02),
    BITFIELD((byte) 0x03),
    HAVE((byte) 0x04),
    REQUEST((byte) 0x05),
    BLOCK((byte) 0x06),
    CANCEL((byte) 0x07),
    PING((byte) 0x08),
    PONG((byte) 0x09),
    ERROR((byte) 0x0A);

    private final byte id;

    MessageType(byte id) {
        this.id = id;
    }

    public byte id() {
        return id;
    }

    public static MessageType fromId(byte id) {
        for (MessageType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new ProtocolViolationException("unknown message type: " + (id & 0xFF));
    }
}
