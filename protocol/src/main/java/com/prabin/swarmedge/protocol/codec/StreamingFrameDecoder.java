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
 * Stateful protocol v1 decoder that streams BLOCK payloads (blueprint §7.3).
 *
 * <p>{@link FrameDecoder} waits for a whole frame before emitting anything, which is
 * right for control messages and wrong for bulk data: it would mean holding a block,
 * and later a whole chunk, in one buffer. This decoder remembers where it is in the
 * frame instead, so a payload can be handed onward in whatever pieces TCP delivers.
 *
 * <p>Control messages still arrive as a complete {@link PeerFrame}. BLOCK arrives as
 * {@link BlockStream.Begin}, one or more {@link BlockStream.Data}, then
 * {@link BlockStream.End}.
 *
 * <p>Allocation is bounded twice over: {@code frameLength} is range-checked before
 * anything is sized from it, and a payload array is only allocated once its bytes are
 * actually readable. A peer that announces a megabyte and then goes quiet costs nothing.
 */
public final class StreamingFrameDecoder extends ByteToMessageDecoder {

    private enum Stage {
        LENGTH,
        HEADER,
        CONTROL_PAYLOAD,
        BLOCK_META,
        BLOCK_PAYLOAD
    }

    private final int maxFrameLength;
    private final int maxBlockSize;

    private Stage stage = Stage.LENGTH;
    private int frameLength;
    private MessageType type;
    private int flags;
    private long requestId;
    private int chunkIndex;
    private int blockOffset;
    private int blockLength;
    private int delivered;

    public StreamingFrameDecoder() {
        this(ProtocolLimits.MAX_FRAME_LENGTH, ProtocolLimits.absoluteMaxBlockSize());
    }

    /**
     * @param maxFrameLength largest accepted frame, never above the protocol maximum
     * @param maxBlockSize   negotiated block ceiling from HELLO_ACK; a peer that sends
     *                       more than it was promised is a protocol violation
     */
    public StreamingFrameDecoder(int maxFrameLength, int maxBlockSize) {
        if (maxFrameLength < ProtocolLimits.HEADER_AFTER_LENGTH || maxFrameLength > ProtocolLimits.MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("maxFrameLength out of range: " + maxFrameLength);
        }
        if (maxBlockSize <= 0 || maxBlockSize > ProtocolLimits.absoluteMaxBlockSize()) {
            throw new IllegalArgumentException("maxBlockSize out of range: " + maxBlockSize);
        }
        this.maxFrameLength = maxFrameLength;
        this.maxBlockSize = maxBlockSize;
    }

    /** True between Begin and End, so a dropped connection can be reported as a partial block. */
    public boolean streamingBlock() {
        return stage == Stage.BLOCK_PAYLOAD;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        boolean progress = true;
        while (progress) {
            progress = switch (stage) {
                case LENGTH -> readLength(in);
                case HEADER -> readHeader(in);
                case CONTROL_PAYLOAD -> readControlPayload(in, out);
                case BLOCK_META -> readBlockMeta(in, out);
                case BLOCK_PAYLOAD -> streamBlockPayload(in, out);
            };
        }
    }

    private boolean readLength(ByteBuf in) {
        if (in.readableBytes() < Integer.BYTES) {
            return false;
        }
        long declared = in.readUnsignedInt();
        if (declared < ProtocolLimits.HEADER_AFTER_LENGTH || declared > maxFrameLength) {
            throw new ProtocolViolationException("illegal frameLength: " + declared);
        }
        frameLength = (int) declared;
        stage = Stage.HEADER;
        return true;
    }

    private boolean readHeader(ByteBuf in) {
        if (in.readableBytes() < ProtocolLimits.HEADER_AFTER_LENGTH) {
            return false;
        }
        byte version = in.readByte();
        if (version != Defaults.PROTOCOL_VERSION) {
            throw new ProtocolViolationException("unsupported protocol version: " + (version & 0xFF));
        }
        type = MessageType.fromId(in.readByte());
        flags = in.readUnsignedShort();
        requestId = in.readLong();
        if (type != MessageType.BLOCK) {
            stage = Stage.CONTROL_PAYLOAD;
            return true;
        }
        if (payloadLength() < ProtocolLimits.BLOCK_META_BYTES) {
            throw new ProtocolViolationException("BLOCK metadata truncated");
        }
        stage = Stage.BLOCK_META;
        return true;
    }

    private boolean readControlPayload(ByteBuf in, List<Object> out) {
        int payloadLength = payloadLength();
        if (in.readableBytes() < payloadLength) {
            return false;
        }
        byte[] payload = new byte[payloadLength];
        in.readBytes(payload);
        out.add(new PeerFrame((byte) Defaults.PROTOCOL_VERSION, type, flags, requestId, payload));
        stage = Stage.LENGTH;
        return true;
    }

    private boolean readBlockMeta(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < ProtocolLimits.BLOCK_META_BYTES) {
            return false;
        }
        // Read as unsigned and validate before narrowing, so a u32 near 2^32 cannot
        // arrive as a negative int and be mistaken for something small (blueprint §21.2).
        long declaredIndex = in.readUnsignedInt();
        long declaredOffset = in.readUnsignedInt();
        long declaredLength = in.readUnsignedInt();

        // The declared payload size and the frame size must agree, or framing is a guess.
        long expected = payloadLength() - ProtocolLimits.BLOCK_META_BYTES;
        if (declaredLength != expected) {
            throw new ProtocolViolationException(
                    "BLOCK length disagrees with frameLength: " + declaredLength + " vs " + expected);
        }
        if (declaredLength == 0 || declaredLength > maxBlockSize) {
            throw new ProtocolViolationException("illegal blockLength: " + declaredLength);
        }
        if (declaredOffset + declaredLength > 0xFFFF_FFFFL) {
            throw new ProtocolViolationException("blockOffset+blockLength overflow");
        }
        // Beyond this the session checks the pair against the manifest; here we only
        // guarantee the fields are usable as signed offsets.
        if (declaredIndex > Integer.MAX_VALUE || declaredOffset > Integer.MAX_VALUE) {
            throw new ProtocolViolationException("BLOCK index or offset is out of addressable range");
        }

        chunkIndex = (int) declaredIndex;
        blockOffset = (int) declaredOffset;
        blockLength = (int) declaredLength;
        delivered = 0;
        out.add(new BlockStream.Begin(requestId, chunkIndex, blockOffset, blockLength));
        stage = Stage.BLOCK_PAYLOAD;
        return true;
    }

    private boolean streamBlockPayload(ByteBuf in, List<Object> out) {
        int remaining = blockLength - delivered;
        int available = Math.min(in.readableBytes(), remaining);
        if (available == 0) {
            return false;
        }
        byte[] piece = new byte[available];
        in.readBytes(piece);
        out.add(new BlockStream.Data(requestId, delivered, piece));
        delivered += available;
        if (delivered == blockLength) {
            out.add(new BlockStream.End(requestId, chunkIndex, blockOffset, blockLength));
            stage = Stage.LENGTH;
        }
        return true;
    }

    private int payloadLength() {
        return frameLength - ProtocolLimits.HEADER_AFTER_LENGTH;
    }
}
