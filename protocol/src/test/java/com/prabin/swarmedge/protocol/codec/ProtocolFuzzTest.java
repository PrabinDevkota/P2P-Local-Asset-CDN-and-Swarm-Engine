package com.prabin.swarmedge.protocol.codec;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.protocol.PeerFrame;
import com.prabin.swarmedge.protocol.ProtocolLimits;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import com.prabin.swarmedge.protocol.msg.Messages;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-06: hostile and malformed input must fail closed rather than crash, and must never
 * make the decoder allocate more than it was actually sent.
 *
 * <p>The allocation rule is the one worth stating plainly: a frame header declares how
 * long the payload will be, and an attacker controls that number. The decoder is only
 * allowed to size buffers from bytes it already holds, so the total it hands onward can
 * never exceed the total it received.
 */
class ProtocolFuzzTest {

    private static final int FUZZ_ROUNDS = 500;

    @Test
    void randomBytesOnlyEverProduceAProtocolViolation() {
        for (int seed = 0; seed < FUZZ_ROUNDS; seed++) {
            Random random = new Random(seed);
            byte[] noise = new byte[1 + random.nextInt(4_096)];
            random.nextBytes(noise);

            Outcome outcome = feed(noise, random);

            assertThat(outcome.fatal())
                    .as("seed %s produced %s", seed, outcome.failure())
                    .isFalse();
        }
    }

    @Test
    void theDecoderNeverHandsOnMoreBytesThanItWasGiven() {
        for (int seed = 0; seed < FUZZ_ROUNDS; seed++) {
            Random random = new Random(1_000 + seed);
            byte[] noise = new byte[1 + random.nextInt(8_192)];
            random.nextBytes(noise);
            // Bias towards plausible-looking headers so declared lengths are often huge.
            if (noise.length > 4) {
                noise[0] = 0;
                noise[1] = (byte) random.nextInt(16);
                noise[4] = 1;
            }

            Outcome outcome = feed(noise, random);

            assertThat(outcome.bytesEmitted())
                    .as("seed %s amplified %s bytes into %s", seed, noise.length, outcome.bytesEmitted())
                    .isLessThanOrEqualTo(noise.length);
        }
    }

    @Test
    void aStreamOfHugeDeclarationsWithNoPayloadEmitsNothing() {
        EmbeddedChannel channel = new EmbeddedChannel(new StreamingFrameDecoder());
        byte[] header = HexFormat.of().parseHex(
                "000ffffc" + "01" + "04" + "0000" + "0000000000000000");

        for (int i = 0; i < 1_000; i++) {
            channel.writeInbound(Unpooled.wrappedBuffer(header.clone()));
        }

        assertThat((Object) channel.readInbound()).isNull();
        channel.finish();
    }

    @Test
    void wellFormedFramesDecodeIdenticallyAtEverySplitPoint() {
        List<Object> expected = expectedEvents();
        byte[] wire = conformanceStream();

        for (int split = 1; split < wire.length; split++) {
            EmbeddedChannel channel = new EmbeddedChannel(new StreamingFrameDecoder());
            channel.writeInbound(Unpooled.wrappedBuffer(wire, 0, split));
            channel.writeInbound(Unpooled.wrappedBuffer(wire, split, wire.length - split));

            List<Object> decoded = drain(channel);

            assertThat(describe(decoded))
                    .as("split at byte %s of %s", split, wire.length)
                    .isEqualTo(describe(expected));
            channel.finish();
        }
    }

    @Test
    void everyTruncationOfAValidStreamWaitsInsteadOfGuessing() {
        byte[] wire = conformanceStream();

        for (int length = 1; length < wire.length; length++) {
            EmbeddedChannel channel = new EmbeddedChannel(new StreamingFrameDecoder());

            channel.writeInbound(Unpooled.wrappedBuffer(wire, 0, length));

            // A prefix may decode complete frames, but never more bytes than it carried.
            long emitted = payloadBytes(drain(channel));
            assertThat(emitted).as("prefix of %s bytes", length).isLessThanOrEqualTo(length);
            channel.finish();
        }
    }

    @Test
    void everySingleByteFlipIsEitherHarmlessOrRefusedCleanly() {
        byte[] wire = conformanceStream();

        for (int index = 0; index < wire.length; index++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] mutated = wire.clone();
                mutated[index] ^= (byte) (1 << bit);

                Outcome outcome = feed(mutated, new Random(index * 8L + bit));

                assertThat(outcome.fatal())
                        .as("flipping bit %s of byte %s produced %s", bit, index, outcome.failure())
                        .isFalse();
                assertThat(outcome.bytesEmitted()).isLessThanOrEqualTo(mutated.length);
            }
        }
    }

    /** Feed bytes in random-sized pieces and report what came out. */
    private static Outcome feed(byte[] bytes, Random random) {
        EmbeddedChannel channel = new EmbeddedChannel(new StreamingFrameDecoder());
        long emitted = 0;
        Throwable failure = null;
        try {
            int offset = 0;
            while (offset < bytes.length) {
                int piece = 1 + random.nextInt(Math.min(512, bytes.length - offset));
                channel.writeInbound(Unpooled.wrappedBuffer(bytes, offset, piece));
                offset += piece;
                emitted += payloadBytes(drain(channel));
            }
            emitted += payloadBytes(drain(channel));
        } catch (Throwable thrown) {
            failure = thrown;
        }
        try {
            // EmbeddedChannel replays a recorded failure on close, so catch it here too.
            channel.finishAndReleaseAll();
        } catch (Throwable thrown) {
            failure = failure == null ? thrown : failure;
        }
        return new Outcome(failure != null && !isViolation(failure), failure, emitted);
    }

    private static long payloadBytes(List<Object> events) {
        long total = 0;
        for (Object event : events) {
            if (event instanceof PeerFrame frame) {
                total += frame.payload().length;
            } else if (event instanceof BlockStream.Data data) {
                total += data.bytes().length;
            }
        }
        return total;
    }

    private static byte[] conformanceStream() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (PeerFrame frame : conformanceFrames()) {
            byte[] encoded = Frames.encode(frame);
            out.write(encoded, 0, encoded.length);
        }
        return out.toByteArray();
    }

    private static List<PeerFrame> conformanceFrames() {
        return List.of(
                Messages.hello(AssetId.of(filled((byte) 0xAA, 32)), PeerId.of(filled((byte) 0xBB, 16)),
                        "token".getBytes(StandardCharsets.US_ASCII), 0, 1).frame(),
                Messages.helloAck(true, 1_024, 0, 1).frame(),
                Messages.bitfield(6, new byte[] {(byte) 0xFC}, 0).frame(),
                Messages.request(2, 1_024, 1_024, 7).frame(),
                Messages.block(2, 1_024, filled((byte) 0x5A, 64), 7).frame(),
                Messages.have(2).frame(),
                Messages.cancel(8).frame(),
                Messages.ping(0x0102030405060708L).frame());
    }

    private static List<Object> expectedEvents() {
        List<Object> events = new ArrayList<>();
        for (PeerFrame frame : conformanceFrames()) {
            if (frame.type() == com.prabin.swarmedge.protocol.MessageType.BLOCK) {
                events.add(new BlockStream.Begin(frame.requestId(), 2, 1_024, 64));
                events.add(new BlockStream.Data(frame.requestId(), 0, filled((byte) 0x5A, 64)));
                events.add(new BlockStream.End(frame.requestId(), 2, 1_024, 64));
            } else {
                events.add(frame);
            }
        }
        return events;
    }

    /**
     * Compare by description rather than equality: payload arrays split differently at
     * different offsets, so what matters is the sequence and the bytes, not the pieces.
     */
    private static String describe(List<Object> events) {
        StringBuilder text = new StringBuilder();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (Object event : events) {
            if (event instanceof BlockStream.Data data) {
                payload.write(data.bytes(), 0, data.bytes().length);
                continue;
            }
            if (event instanceof BlockStream.Begin begin) {
                text.append("BEGIN(").append(begin.chunkIndex()).append(',')
                        .append(begin.blockOffset()).append(',').append(begin.blockLength()).append(")\n");
            } else if (event instanceof BlockStream.End end) {
                text.append("END(").append(end.blockLength()).append(")\n");
            } else if (event instanceof PeerFrame frame) {
                text.append(frame.type()).append('(').append(frame.requestId()).append(',')
                        .append(HexFormat.of().formatHex(frame.payload())).append(")\n");
            }
        }
        text.append("payload=").append(HexFormat.of().formatHex(payload.toByteArray()));
        return text.toString();
    }

    private static List<Object> drain(EmbeddedChannel channel) {
        List<Object> events = new ArrayList<>();
        Object next;
        while ((next = channel.readInbound()) != null) {
            events.add(next);
        }
        return events;
    }

    private static boolean isViolation(Throwable thrown) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current instanceof ProtocolViolationException) {
                return true;
            }
        }
        return false;
    }

    private static byte[] filled(byte value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, value);
        return out;
    }

    private record Outcome(boolean fatal, Throwable failure, long bytesEmitted) {
    }
}
