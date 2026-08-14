package com.prabin.swarmedge.protocol;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.protocol.codec.FrameDecoder;
import com.prabin.swarmedge.protocol.codec.FrameEncoder;
import com.prabin.swarmedge.protocol.codec.Frames;
import com.prabin.swarmedge.protocol.msg.Messages;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolGoldenVectorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "have", "cancel", "ping", "request", "hello_ack", "bitfield", "block", "error", "hello"
    })
    void encoderMatchesCommittedVector(String name) {
        byte[] expected = readVector(name);
        byte[] actual = Frames.encode(sample(name).frame());
        assertThat(HexFormat.of().formatHex(actual)).isEqualTo(HexFormat.of().formatHex(expected));
    }

    @Test
    void embeddedChannelRoundTripAndPartialFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), new FrameEncoder());
        PeerFrame have = Messages.have(7).frame();
        assertThat(channel.writeOutbound(have)).isTrue();
        Object outbound = channel.readOutbound();
        byte[] encoded = new byte[((io.netty.buffer.ByteBuf) outbound).readableBytes()];
        ((io.netty.buffer.ByteBuf) outbound).readBytes(encoded);
        ((io.netty.buffer.ByteBuf) outbound).release();

        channel.writeInbound(Unpooled.wrappedBuffer(encoded, 0, 3));
        org.junit.jupiter.api.Assertions.assertNull(channel.readInbound());
        channel.writeInbound(Unpooled.wrappedBuffer(encoded, 3, encoded.length - 3));
        PeerFrame decoded = channel.readInbound();
        assertThat(decoded).isEqualTo(have);

        byte[] twice = new byte[encoded.length * 2];
        System.arraycopy(encoded, 0, twice, 0, encoded.length);
        System.arraycopy(encoded, 0, twice, encoded.length, encoded.length);
        channel.writeInbound(Unpooled.wrappedBuffer(twice));
        assertThat((PeerFrame) channel.readInbound()).isEqualTo(have);
        assertThat((PeerFrame) channel.readInbound()).isEqualTo(have);
        channel.finish();
    }

    @Test
    void rejectsOversizedFrameLength() {
        byte[] header = HexFormat.of().parseHex("7fffffff010400000000000000000000");
        EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());
        assertThatThrownBy(() -> channel.writeInbound(Unpooled.wrappedBuffer(header)))
                .satisfies(t -> {
                    Throwable cur = t;
                    boolean found = false;
                    while (cur != null) {
                        if (cur instanceof ProtocolViolationException) {
                            found = true;
                            break;
                        }
                        cur = cur.getCause();
                    }
                    assertThat(found).isTrue();
                });
    }

    private static Sample sample(String name) {
        return switch (name) {
            case "have" -> new Sample(Messages.have(7).frame());
            case "cancel" -> new Sample(Messages.cancel(99).frame());
            case "ping" -> new Sample(Messages.ping(0x0102030405060708L).frame());
            case "request" -> new Sample(Messages.request(1, 262144, 262144, 42).frame());
            case "hello_ack" -> new Sample(Messages.helloAck(true, 262144, 0, 1).frame());
            case "bitfield" -> new Sample(Messages.bitfield(8, new byte[] {(byte) 0x80}, 0).frame());
            case "block" -> new Sample(Messages.block(0, 0, HexFormat.of().parseHex("deadbeef"), 7).frame());
            case "error" -> new Sample(Messages.error(1, "bad", 1).frame());
            case "hello" -> new Sample(Messages.hello(
                    AssetId.of(bytes((byte) 0xAA, 32)),
                    PeerId.of(bytes((byte) 0xBB, 16)),
                    "ab".getBytes(StandardCharsets.US_ASCII),
                    1,
                    1
            ).frame());
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static byte[] bytes(byte value, int length) {
        byte[] out = new byte[length];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static byte[] readVector(String name) {
        try (var in = ProtocolGoldenVectorTest.class.getResourceAsStream("/vectors/" + name + ".hex")) {
            String hex = new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
            return HexFormat.of().parseHex(hex);
        } catch (Exception e) {
            throw new IllegalStateException(name, e);
        }
    }

    private record Sample(PeerFrame frame) {
    }
}
