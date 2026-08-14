package com.prabin.swarmedge.protocol.codec;

import com.prabin.swarmedge.common.Defaults;
import com.prabin.swarmedge.protocol.MessageType;
import com.prabin.swarmedge.protocol.PeerFrame;
import com.prabin.swarmedge.protocol.ProtocolLimits;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Length-prefixed protocol v1 decoder.
 * frameLength is the number of bytes AFTER the 4-byte length field.
 */
public final class FrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return;
        }
        in.markReaderIndex();
        long frameLength = in.readUnsignedInt();
        if (frameLength < ProtocolLimits.HEADER_AFTER_LENGTH || frameLength > ProtocolLimits.MAX_FRAME_LENGTH) {
            throw new ProtocolViolationException("illegal frameLength: " + frameLength);
        }
        if (in.readableBytes() < frameLength) {
            in.resetReaderIndex();
            return;
        }
        byte version = in.readByte();
        if (version != Defaults.PROTOCOL_VERSION) {
            throw new ProtocolViolationException("unsupported protocol version: " + (version & 0xFF));
        }
        MessageType type = MessageType.fromId(in.readByte());
        int flags = in.readUnsignedShort();
        long requestId = in.readLong();
        int payloadLength = (int) frameLength - ProtocolLimits.HEADER_AFTER_LENGTH;
        byte[] payload = new byte[payloadLength];
        in.readBytes(payload);
        out.add(new PeerFrame(version, type, flags, requestId, payload));
    }
}
