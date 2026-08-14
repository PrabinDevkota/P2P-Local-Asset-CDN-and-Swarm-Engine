package com.prabin.swarmedge.protocol.codec;

import com.prabin.swarmedge.protocol.PeerFrame;
import com.prabin.swarmedge.protocol.ProtocolLimits;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class Frames {

    private Frames() {
    }

    public static byte[] encode(PeerFrame frame) {
        byte[] payload = frame.payload();
        int frameLength = ProtocolLimits.HEADER_AFTER_LENGTH + payload.length;
        if (frameLength > ProtocolLimits.MAX_FRAME_LENGTH) {
            throw new ProtocolViolationException("frameLength exceeds maximum: " + frameLength);
        }
        ByteBuf out = Unpooled.buffer(4 + frameLength);
        out.writeInt(frameLength);
        out.writeByte(frame.version());
        out.writeByte(frame.type().id());
        out.writeShort(frame.flags());
        out.writeLong(frame.requestId());
        out.writeBytes(payload);
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        out.release();
        return bytes;
    }
}
