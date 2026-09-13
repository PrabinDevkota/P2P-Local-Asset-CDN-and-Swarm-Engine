package com.prabin.swarmedge.protocol.codec;

import com.prabin.swarmedge.protocol.MessageType;
import com.prabin.swarmedge.protocol.PeerFrame;
import com.prabin.swarmedge.protocol.ProtocolLimits;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import com.prabin.swarmedge.protocol.msg.Messages;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamingFrameDecoderTest {

    private static final int CHUNK_INDEX = 3;
    private static final int BLOCK_OFFSET = 262_144;
    private static final long REQUEST_ID = 77L;

    @Test
    void controlFramesStillArriveWhole() {
        EmbeddedChannel channel = channel();
        PeerFrame have = Messages.have(9).frame();

        channel.writeInbound(Unpooled.wrappedBuffer(Frames.encode(have)));

        assertThat((PeerFrame) channel.readInbound()).isEqualTo(have);
        assertThat((Object) channel.readInbound()).isNull();
        channel.finish();
    }

    @Test
    void aBlockFedOneByteAtATimeIsRebuiltExactly() {
        byte[] payload = deterministicBytes(5_000);
        byte[] wire = blockWire(payload);
        EmbeddedChannel channel = channel();

        for (byte b : wire) {
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {b}));
        }

        List<Object> events = drain(channel);
        assertThat(events.getFirst())
                .isEqualTo(new BlockStream.Begin(REQUEST_ID, CHUNK_INDEX, BLOCK_OFFSET, payload.length));
        assertThat(events.getLast())
                .isEqualTo(new BlockStream.End(REQUEST_ID, CHUNK_INDEX, BLOCK_OFFSET, payload.length));
        assertThat(reassemble(events)).containsExactly(payload);
        // One byte per read means the payload never sat in a single buffer.
        assertThat(events).hasSize(payload.length + 2);
        channel.finish();
    }

    @Test
    void dataSlicesCarryTheirOffsetSoBytesCanBePlacedWithoutCounting() {
        byte[] payload = deterministicBytes(300);
        byte[] wire = blockWire(payload);
        EmbeddedChannel channel = channel();

        int split = wire.length - 100;
        channel.writeInbound(Unpooled.wrappedBuffer(wire, 0, split));
        channel.writeInbound(Unpooled.wrappedBuffer(wire, split, wire.length - split));

        List<BlockStream.Data> data = drain(channel).stream()
                .filter(BlockStream.Data.class::isInstance)
                .map(BlockStream.Data.class::cast)
                .toList();
        assertThat(data).hasSize(2);
        assertThat(data.get(0).offsetInBlock()).isZero();
        assertThat(data.get(1).offsetInBlock()).isEqualTo(200);
        assertThat(data.get(1).bytes()).hasSize(100);
        channel.finish();
    }

    @Test
    void coalescedFramesAreAllDecodedFromOneRead() {
        byte[] payload = deterministicBytes(1_024);
        EmbeddedChannel channel = channel();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        write(wire, Frames.encode(Messages.have(1).frame()));
        write(wire, blockWire(payload));
        write(wire, Frames.encode(Messages.have(2).frame()));

        channel.writeInbound(Unpooled.wrappedBuffer(wire.toByteArray()));

        List<Object> events = drain(channel);
        assertThat(events.getFirst()).isEqualTo(Messages.have(1).frame());
        assertThat(events.getLast()).isEqualTo(Messages.have(2).frame());
        assertThat(reassemble(events)).containsExactly(payload);
        channel.finish();
    }

    @Test
    void backToBackBlocksDoNotBleedIntoEachOther() {
        byte[] first = deterministicBytes(700);
        byte[] second = deterministicBytes(900);
        EmbeddedChannel channel = channel();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        write(wire, blockWire(first));
        write(wire, blockWire(second));

        channel.writeInbound(Unpooled.wrappedBuffer(wire.toByteArray()));

        List<Object> events = drain(channel);
        assertThat(events.stream().filter(BlockStream.End.class::isInstance)).hasSize(2);
        byte[] expected = new byte[first.length + second.length];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        assertThat(reassemble(events)).containsExactly(expected);
        channel.finish();
    }

    @Test
    void midBlockIsVisibleSoAnAbortCanBeReportedAsPartial() {
        StreamingFrameDecoder decoder = new StreamingFrameDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);
        byte[] wire = blockWire(deterministicBytes(400));

        assertThat(decoder.streamingBlock()).isFalse();
        channel.writeInbound(Unpooled.wrappedBuffer(wire, 0, wire.length - 10));
        assertThat(decoder.streamingBlock()).isTrue();
        channel.writeInbound(Unpooled.wrappedBuffer(wire, wire.length - 10, 10));
        assertThat(decoder.streamingBlock()).isFalse();
        channel.finish();
    }

    @Test
    void anAnnouncedFrameThatNeverArrivesEmitsNothingAndAllocatesNothing() {
        EmbeddedChannel channel = channel();
        // frameLength says a megabyte; only the header follows.
        byte[] header = HexFormat.of().parseHex("000ffffc" + "01" + "04" + "0000" + "0000000000000000");

        channel.writeInbound(Unpooled.wrappedBuffer(header));

        assertThat((Object) channel.readInbound()).isNull();
        channel.finish();
    }

    @Test
    void rejectsAFrameLengthBelowTheFixedHeader() {
        assertViolation(() -> channel().writeInbound(Unpooled.wrappedBuffer(
                HexFormat.of().parseHex("0000000b"))));
    }

    @Test
    void rejectsAFrameLengthAboveTheMaximum() {
        assertViolation(() -> channel().writeInbound(Unpooled.wrappedBuffer(
                HexFormat.of().parseHex("7fffffff"))));
    }

    @Test
    void rejectsAnUnknownVersionAndAnUnknownType() {
        assertViolation(() -> channel().writeInbound(Unpooled.wrappedBuffer(
                HexFormat.of().parseHex("00000010" + "02" + "04" + "0000" + "0000000000000000" + "00000001"))));
        assertViolation(() -> channel().writeInbound(Unpooled.wrappedBuffer(
                HexFormat.of().parseHex("00000010" + "01" + "ff" + "0000" + "0000000000000000" + "00000001"))));
    }

    @Test
    void rejectsBlockMetadataThatDisagreesWithTheFrameLength() {
        byte[] wire = blockWire(deterministicBytes(64));
        // Claim one more payload byte than the frame carries.
        wire[wire.length - 64 - 1]++;

        assertViolation(() -> channel().writeInbound(Unpooled.wrappedBuffer(wire)));
    }

    @Test
    void rejectsABlockLargerThanWasNegotiated() {
        byte[] wire = blockWire(deterministicBytes(4_096));
        EmbeddedChannel small = new EmbeddedChannel(new StreamingFrameDecoder(ProtocolLimits.MAX_FRAME_LENGTH, 1_024));

        assertViolation(() -> small.writeInbound(Unpooled.wrappedBuffer(wire)));
    }

    @Test
    void rejectsAnEmptyBlockAndTruncatedBlockMetadata() {
        assertViolation(() -> channel().writeInbound(
                Unpooled.wrappedBuffer(blockFrame(CHUNK_INDEX, 0, 0, new byte[0]))));
        // frameLength leaves room for only 8 of the 12 metadata bytes.
        assertViolation(() -> channel().writeInbound(Unpooled.wrappedBuffer(
                HexFormat.of().parseHex("00000014" + "01" + "06" + "0000" + "0000000000000000"))));
    }

    @Test
    void rejectsABlockWhoseOffsetPlusLengthOverflowsTheWireField() {
        assertViolation(() -> channel().writeInbound(Unpooled.wrappedBuffer(
                blockFrame(0, 0xFFFF_FFF0L, 64, new byte[64]))));
    }

    @Test
    void rejectsAnOffsetThatNoChunkCouldEverHold() {
        assertViolation(() -> channel().writeInbound(Unpooled.wrappedBuffer(
                blockFrame(0, 3_000_000_000L, 64, new byte[64]))));
        assertViolation(() -> channel().writeInbound(Unpooled.wrappedBuffer(
                blockFrame(3_000_000_000L, 0, 64, new byte[64]))));
    }

    @Test
    void refusesToBeBuiltWithLimitsTheProtocolDoesNotAllow() {
        assertThatThrownBy(() -> new StreamingFrameDecoder(ProtocolLimits.MAX_FRAME_LENGTH + 1, 1_024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamingFrameDecoder(ProtocolLimits.MAX_FRAME_LENGTH,
                ProtocolLimits.absoluteMaxBlockSize() + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamingFrameDecoder(ProtocolLimits.MAX_FRAME_LENGTH, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new StreamingFrameDecoder());
    }

    private static byte[] blockWire(byte[] payload) {
        return Frames.encode(Messages.block(CHUNK_INDEX, BLOCK_OFFSET, payload, REQUEST_ID).frame());
    }

    /** Hand-built BLOCK frame so tests can declare field values the encoder refuses. */
    private static byte[] blockFrame(long chunkIndex, long blockOffset, long declaredLength, byte[] payload) {
        int frameLength = ProtocolLimits.HEADER_AFTER_LENGTH + ProtocolLimits.BLOCK_META_BYTES + payload.length;
        io.netty.buffer.ByteBuf buf = Unpooled.buffer();
        buf.writeInt(frameLength);
        buf.writeByte(1);
        buf.writeByte(MessageType.BLOCK.id());
        buf.writeShort(0);
        buf.writeLong(REQUEST_ID);
        buf.writeInt((int) chunkIndex);
        buf.writeInt((int) blockOffset);
        buf.writeInt((int) declaredLength);
        buf.writeBytes(payload);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }

    private static List<Object> drain(EmbeddedChannel channel) {
        List<Object> events = new ArrayList<>();
        Object next;
        while ((next = channel.readInbound()) != null) {
            events.add(next);
        }
        return events;
    }

    private static byte[] reassemble(List<Object> events) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object event : events) {
            if (event instanceof BlockStream.Data data) {
                out.write(data.bytes(), 0, data.bytes().length);
            }
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] deterministicBytes(int length) {
        byte[] out = new byte[length];
        new Random(length).nextBytes(out);
        return out;
    }

    private static void assertViolation(Runnable action) {
        assertThatThrownBy(action::run).satisfies(thrown -> {
            Throwable cause = thrown;
            while (cause != null && !(cause instanceof ProtocolViolationException)) {
                cause = cause.getCause();
            }
            assertThat(cause).as("expected a ProtocolViolationException in %s", thrown).isNotNull();
        });
    }
}
