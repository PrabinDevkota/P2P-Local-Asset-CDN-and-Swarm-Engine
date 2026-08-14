package com.prabin.swarmedge.protocol.msg;

import com.prabin.swarmedge.common.Defaults;
import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.protocol.MessageType;
import com.prabin.swarmedge.protocol.PeerFrame;
import com.prabin.swarmedge.protocol.ProtocolLimits;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public final class Messages {

    private Messages() {
    }

    public static PeerFrame frame(MessageType type, long requestId, byte[] payload) {
        return new PeerFrame((byte) Defaults.PROTOCOL_VERSION, type, 0, requestId, payload);
    }

    public static Hello hello(AssetId assetId, PeerId peerId, byte[] token, int capabilities, long requestId) {
        Objects.requireNonNull(token, "token");
        if (token.length > ProtocolLimits.MAX_TOKEN_BYTES) {
            throw new ProtocolViolationException("token too long");
        }
        ByteBuf buf = Unpooled.buffer(32 + 16 + 2 + token.length + 4);
        buf.writeBytes(assetId.bytes());
        buf.writeBytes(peerId.bytes());
        buf.writeShort(token.length);
        buf.writeBytes(token);
        buf.writeInt(capabilities);
        return new Hello(assetId, peerId, token.clone(), capabilities, frame(MessageType.HELLO, requestId, toArray(buf)));
    }

    public static Hello decodeHello(PeerFrame frame) {
        require(frame, MessageType.HELLO);
        ByteBuf buf = Unpooled.wrappedBuffer(frame.payload());
        if (buf.readableBytes() < 32 + 16 + 2 + 4) {
            throw new ProtocolViolationException("HELLO payload truncated");
        }
        byte[] asset = new byte[32];
        buf.readBytes(asset);
        byte[] peer = new byte[16];
        buf.readBytes(peer);
        int tokenLen = buf.readUnsignedShort();
        if (tokenLen > ProtocolLimits.MAX_TOKEN_BYTES || buf.readableBytes() < tokenLen + 4) {
            throw new ProtocolViolationException("HELLO token length invalid");
        }
        byte[] token = new byte[tokenLen];
        buf.readBytes(token);
        int capabilities = buf.readInt();
        if (buf.isReadable()) {
            throw new ProtocolViolationException("HELLO trailing bytes");
        }
        return new Hello(AssetId.of(asset), PeerId.of(peer), token, capabilities, frame);
    }

    public static HelloAck helloAck(boolean accepted, int maxBlockSize, int reasonCode, long requestId) {
        checkMaxBlock(maxBlockSize);
        ByteBuf buf = Unpooled.buffer(7);
        buf.writeByte(accepted ? 1 : 0);
        buf.writeInt(maxBlockSize);
        buf.writeShort(reasonCode);
        return new HelloAck(accepted, maxBlockSize, reasonCode, frame(MessageType.HELLO_ACK, requestId, toArray(buf)));
    }

    public static HelloAck decodeHelloAck(PeerFrame frame) {
        require(frame, MessageType.HELLO_ACK);
        ByteBuf buf = Unpooled.wrappedBuffer(frame.payload());
        if (buf.readableBytes() != 7) {
            throw new ProtocolViolationException("HELLO_ACK payload must be 7 bytes");
        }
        boolean accepted = buf.readUnsignedByte() != 0;
        int maxBlockSize = buf.readInt();
        int reasonCode = buf.readUnsignedShort();
        checkMaxBlock(maxBlockSize);
        return new HelloAck(accepted, maxBlockSize, reasonCode, frame);
    }

    public static Bitfield bitfield(int bitCount, byte[] bits, long requestId) {
        int expected = bitfieldByteLength(bitCount);
        if (bits.length != expected) {
            throw new ProtocolViolationException("BITFIELD byteLength mismatch");
        }
        ByteBuf buf = Unpooled.buffer(8 + bits.length);
        buf.writeInt(bitCount);
        buf.writeInt(bits.length);
        buf.writeBytes(bits);
        return new Bitfield(bitCount, bits.clone(), frame(MessageType.BITFIELD, requestId, toArray(buf)));
    }

    public static Bitfield decodeBitfield(PeerFrame frame) {
        require(frame, MessageType.BITFIELD);
        ByteBuf buf = Unpooled.wrappedBuffer(frame.payload());
        if (buf.readableBytes() < 8) {
            throw new ProtocolViolationException("BITFIELD truncated");
        }
        int bitCount = buf.readInt();
        int byteLength = buf.readInt();
        if (bitCount < 0 || byteLength != bitfieldByteLength(bitCount) || buf.readableBytes() != byteLength) {
            throw new ProtocolViolationException("BITFIELD length invalid");
        }
        byte[] bits = new byte[byteLength];
        buf.readBytes(bits);
        return new Bitfield(bitCount, bits, frame);
    }

    public static Have have(int chunkIndex) {
        checkNonNegative(chunkIndex, "chunkIndex");
        return new Have(chunkIndex, frame(MessageType.HAVE, 0, intBytes(chunkIndex)));
    }

    public static Have decodeHave(PeerFrame frame) {
        require(frame, MessageType.HAVE);
        return new Have(readExactInt(frame.payload(), "HAVE"), frame);
    }

    public static Request request(int chunkIndex, int blockOffset, int blockLength, long requestId) {
        checkRequest(chunkIndex, blockOffset, blockLength);
        ByteBuf buf = Unpooled.buffer(12);
        buf.writeInt(chunkIndex);
        buf.writeInt(blockOffset);
        buf.writeInt(blockLength);
        return new Request(chunkIndex, blockOffset, blockLength, frame(MessageType.REQUEST, requestId, toArray(buf)));
    }

    public static Request decodeRequest(PeerFrame frame) {
        require(frame, MessageType.REQUEST);
        int[] fields = readThreeInts(frame.payload(), "REQUEST");
        checkRequest(fields[0], fields[1], fields[2]);
        return new Request(fields[0], fields[1], fields[2], frame);
    }

    public static Block block(int chunkIndex, int blockOffset, byte[] data, long requestId) {
        Objects.requireNonNull(data, "data");
        checkRequest(chunkIndex, blockOffset, data.length);
        ByteBuf buf = Unpooled.buffer(12 + data.length);
        buf.writeInt(chunkIndex);
        buf.writeInt(blockOffset);
        buf.writeInt(data.length);
        buf.writeBytes(data);
        return new Block(chunkIndex, blockOffset, data.clone(), frame(MessageType.BLOCK, requestId, toArray(buf)));
    }

    public static Block decodeBlock(PeerFrame frame) {
        require(frame, MessageType.BLOCK);
        ByteBuf buf = Unpooled.wrappedBuffer(frame.payload());
        if (buf.readableBytes() < 12) {
            throw new ProtocolViolationException("BLOCK truncated");
        }
        int chunkIndex = buf.readInt();
        int blockOffset = buf.readInt();
        int blockLength = buf.readInt();
        if (buf.readableBytes() != blockLength) {
            throw new ProtocolViolationException("BLOCK length mismatch");
        }
        byte[] data = new byte[blockLength];
        buf.readBytes(data);
        checkRequest(chunkIndex, blockOffset, blockLength);
        return new Block(chunkIndex, blockOffset, data, frame);
    }

    public static Cancel cancel(long requestId) {
        if (requestId == 0) {
            throw new ProtocolViolationException("CANCEL requires non-zero requestId");
        }
        return new Cancel(requestId, frame(MessageType.CANCEL, requestId, new byte[0]));
    }

    public static Cancel decodeCancel(PeerFrame frame) {
        require(frame, MessageType.CANCEL);
        if (frame.payload().length != 0) {
            throw new ProtocolViolationException("CANCEL payload must be empty");
        }
        if (frame.requestId() == 0) {
            throw new ProtocolViolationException("CANCEL requires non-zero requestId");
        }
        return new Cancel(frame.requestId(), frame);
    }

    public static Ping ping(long nonce) {
        return new Ping(nonce, frame(MessageType.PING, 0, longBytes(nonce)));
    }

    public static Ping decodePing(PeerFrame frame) {
        require(frame, MessageType.PING);
        return new Ping(readExactLong(frame.payload(), "PING"), frame);
    }

    public static Pong pong(long nonce) {
        return new Pong(nonce, frame(MessageType.PONG, 0, longBytes(nonce)));
    }

    public static Pong decodePong(PeerFrame frame) {
        require(frame, MessageType.PONG);
        return new Pong(readExactLong(frame.payload(), "PONG"), frame);
    }

    public static ErrorMessage error(int code, String diagnostic, long requestId) {
        byte[] utf8 = diagnostic.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > ProtocolLimits.MAX_ERROR_UTF8_BYTES) {
            throw new ProtocolViolationException("ERROR diagnostic too long");
        }
        ByteBuf buf = Unpooled.buffer(2 + utf8.length);
        buf.writeShort(code);
        buf.writeBytes(utf8);
        return new ErrorMessage(code, diagnostic, frame(MessageType.ERROR, requestId, toArray(buf)));
    }

    public static ErrorMessage decodeError(PeerFrame frame) {
        require(frame, MessageType.ERROR);
        ByteBuf buf = Unpooled.wrappedBuffer(frame.payload());
        if (buf.readableBytes() < 2) {
            throw new ProtocolViolationException("ERROR truncated");
        }
        int code = buf.readUnsignedShort();
        byte[] rest = new byte[buf.readableBytes()];
        buf.readBytes(rest);
        if (rest.length > ProtocolLimits.MAX_ERROR_UTF8_BYTES) {
            throw new ProtocolViolationException("ERROR diagnostic too long");
        }
        return new ErrorMessage(code, new String(rest, StandardCharsets.UTF_8), frame);
    }

    public static int bitfieldByteLength(int bitCount) {
        if (bitCount < 0) {
            throw new ProtocolViolationException("bitCount must be non-negative");
        }
        return (bitCount + 7) / 8;
    }

    private static void require(PeerFrame frame, MessageType type) {
        if (frame.type() != type) {
            throw new ProtocolViolationException("expected " + type);
        }
        if (frame.version() != Defaults.PROTOCOL_VERSION) {
            throw new ProtocolViolationException("unsupported version");
        }
    }

    private static void checkMaxBlock(int maxBlockSize) {
        if (maxBlockSize <= 0 || maxBlockSize > ProtocolLimits.absoluteMaxBlockSize()) {
            throw new ProtocolViolationException("illegal maxBlockSize: " + maxBlockSize);
        }
    }

    private static void checkRequest(int chunkIndex, int blockOffset, int blockLength) {
        checkNonNegative(chunkIndex, "chunkIndex");
        checkNonNegative(blockOffset, "blockOffset");
        if (blockLength <= 0 || blockLength > ProtocolLimits.absoluteMaxBlockSize()) {
            throw new ProtocolViolationException("illegal blockLength: " + blockLength);
        }
        long end = (long) blockOffset + blockLength;
        if (end > 0xFFFF_FFFFL) {
            throw new ProtocolViolationException("blockOffset+blockLength overflow");
        }
    }

    private static void checkNonNegative(int value, String name) {
        if (value < 0) {
            throw new ProtocolViolationException(name + " must be non-negative");
        }
    }

    private static byte[] intBytes(int value) {
        ByteBuf buf = Unpooled.buffer(4);
        buf.writeInt(value);
        return toArray(buf);
    }

    private static byte[] longBytes(long value) {
        ByteBuf buf = Unpooled.buffer(8);
        buf.writeLong(value);
        return toArray(buf);
    }

    private static int readExactInt(byte[] payload, String name) {
        if (payload.length != 4) {
            throw new ProtocolViolationException(name + " payload must be 4 bytes");
        }
        return Unpooled.wrappedBuffer(payload).readInt();
    }

    private static long readExactLong(byte[] payload, String name) {
        if (payload.length != 8) {
            throw new ProtocolViolationException(name + " payload must be 8 bytes");
        }
        return Unpooled.wrappedBuffer(payload).readLong();
    }

    private static int[] readThreeInts(byte[] payload, String name) {
        if (payload.length != 12) {
            throw new ProtocolViolationException(name + " payload must be 12 bytes");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        return new int[] {buf.readInt(), buf.readInt(), buf.readInt()};
    }

    private static byte[] toArray(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }

    public record Hello(AssetId assetId, PeerId peerId, byte[] token, int capabilities, PeerFrame frame) {
        public Hello {
            token = token.clone();
        }

        @Override
        public byte[] token() {
            return token.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Hello other
                    && assetId.equals(other.assetId)
                    && peerId.equals(other.peerId)
                    && Arrays.equals(token, other.token)
                    && capabilities == other.capabilities
                    && frame.equals(other.frame);
        }

        @Override
        public int hashCode() {
            return Objects.hash(assetId, peerId, Arrays.hashCode(token), capabilities, frame);
        }
    }

    public record HelloAck(boolean accepted, int maxBlockSize, int reasonCode, PeerFrame frame) {
    }

    public record Bitfield(int bitCount, byte[] bits, PeerFrame frame) {
        public Bitfield {
            bits = bits.clone();
        }

        @Override
        public byte[] bits() {
            return bits.clone();
        }
    }

    public record Have(int chunkIndex, PeerFrame frame) {
    }

    public record Request(int chunkIndex, int blockOffset, int blockLength, PeerFrame frame) {
    }

    public record Block(int chunkIndex, int blockOffset, byte[] data, PeerFrame frame) {
        public Block {
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    public record Cancel(long requestId, PeerFrame frame) {
    }

    public record Ping(long nonce, PeerFrame frame) {
    }

    public record Pong(long nonce, PeerFrame frame) {
    }

    public record ErrorMessage(int code, String diagnostic, PeerFrame frame) {
    }
}
