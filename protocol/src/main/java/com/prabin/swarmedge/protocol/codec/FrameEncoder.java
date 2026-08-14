package com.prabin.swarmedge.protocol.codec;

import com.prabin.swarmedge.protocol.PeerFrame;
import com.prabin.swarmedge.protocol.ProtocolLimits;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public final class FrameEncoder extends MessageToByteEncoder<PeerFrame> {

    @Override
    protected void encode(ChannelHandlerContext ctx, PeerFrame msg, ByteBuf out) {
        byte[] payload = msg.payload();
        int frameLength = ProtocolLimits.HEADER_AFTER_LENGTH + payload.length;
        if (frameLength > ProtocolLimits.MAX_FRAME_LENGTH) {
            throw new ProtocolViolationException("frameLength exceeds maximum: " + frameLength);
        }
        out.writeInt(frameLength);
        out.writeByte(msg.version());
        out.writeByte(msg.type().id());
        out.writeShort(msg.flags());
        out.writeLong(msg.requestId());
        out.writeBytes(payload);
    }
}
