package com.prabin.swarmedge.peer.net;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.peer.chunk.ChunkAssembler;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.peer.session.BlockSender;
import com.prabin.swarmedge.peer.session.LeecherHandler;
import com.prabin.swarmedge.peer.session.PeerAuthPolicy;
import com.prabin.swarmedge.protocol.MessageType;
import com.prabin.swarmedge.protocol.PeerFrame;
import com.prabin.swarmedge.protocol.ProtocolLimits;
import com.prabin.swarmedge.protocol.codec.Frames;
import com.prabin.swarmedge.protocol.msg.Messages;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-06 at the session level: a peer that sends nonsense gets its connection closed,
 * the seeder survives, and a well-behaved leecher can still complete afterwards.
 *
 * <p>Raw sockets rather than an {@code EmbeddedChannel} on purpose. These cases are
 * about what a real remote peer can do to a listening process, including field values
 * the encoder in this repository refuses to produce.
 */
class SeederBoundsTest {

    private static final int CHUNK_SIZE = 2_048;
    private static final int BLOCK_SIZE = 512;
    private static final int CHUNKS = 4;
    private static final long REQUEST_ID = 42L;
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    private byte[] original;
    private ReleaseManifest manifest;
    private AssetId assetId;
    private ChunkStore seederStore;
    private SeederServer server;

    @BeforeEach
    void startSeeder() throws Exception {
        original = deterministicBytes(CHUNKS * CHUNK_SIZE);
        Path published = Files.write(tempDir.resolve("game-x-1.4.0.bin"), original);
        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        manifest = ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                "2026-09-13T00:00:00Z", "2026-10-13T00:00:00Z", 1, "release-key-2026-01");
        assetId = AssetId.of(filled((byte) 0x11, 32));

        seederStore = new ChunkStore(tempDir.resolve("seeder"));
        for (ChunkEntry chunk : manifest.chunks()) {
            seederStore.putVerified(chunk.sha256(),
                    Arrays.copyOfRange(original, (int) chunk.offset(), (int) (chunk.offset() + chunk.length())));
        }
        server = new SeederServer(new SeederServer.Config(0, assetId, PeerId.of(filled((byte) 1, 16)),
                new ChunkInventory(manifest, seederStore), seederStore, BlockSender.Mode.BUFFERED,
                PeerAuthPolicy.ACCEPT_ANY_TOKEN, BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE * 4));
    }

    @AfterEach
    void stopSeeder() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void aFrameLengthAboveTheMaximumClosesTheConnection() throws Exception {
        assertClosedAfter(bytes(0x7F, 0xFF, 0xFF, 0xFF, 0x01, 0x04, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void aFrameLengthBelowTheFixedHeaderClosesTheConnection() throws Exception {
        assertClosedAfter(bytes(0x00, 0x00, 0x00, 0x0B));
    }

    @Test
    void anUnknownVersionOrTypeClosesTheConnection() throws Exception {
        assertClosedAfter(rawFrame(2, MessageType.HAVE.id(), 0, new byte[] {0, 0, 0, 1}));
        assertClosedAfter(rawFrame(1, (byte) 0x7F, 0, new byte[] {0, 0, 0, 1}));
    }

    @Test
    void aRequestBeforeTheHandshakeClosesTheConnection() throws Exception {
        assertClosedAfter(Frames.encode(Messages.request(0, 0, BLOCK_SIZE, REQUEST_ID).frame()));
    }

    @Test
    void aSecondHelloOnALiveSessionClosesTheConnection() throws Exception {
        try (Conversation peer = handshake()) {
            peer.send(Frames.encode(hello()));

            assertThat(peer.awaitClose()).isTrue();
        }
        assertSeederStillServes();
    }

    @Test
    void aChunkIndexBeyondTheManifestClosesTheConnection() throws Exception {
        try (Conversation peer = handshake()) {
            peer.send(rawRequest(CHUNKS, 0, BLOCK_SIZE));

            assertThat(peer.awaitClose()).isTrue();
        }
        assertSeederStillServes();
    }

    @Test
    void aBlockSpanThatLeavesItsChunkClosesTheConnection() throws Exception {
        try (Conversation peer = handshake()) {
            peer.send(rawRequest(0, CHUNK_SIZE - 1, BLOCK_SIZE));

            assertThat(peer.awaitClose()).isTrue();
        }
        assertSeederStillServes();
    }

    @Test
    void aBlockLengthBeyondWhatWasAdvertisedClosesTheConnection() throws Exception {
        try (Conversation peer = handshake()) {
            peer.send(rawRequest(0, 0, BLOCK_SIZE * 2));

            assertThat(peer.awaitClose()).isTrue();
        }
        assertSeederStillServes();
    }

    @Test
    void aZeroLengthOrZeroIdRequestClosesTheConnection() throws Exception {
        try (Conversation peer = handshake()) {
            peer.send(rawRequest(0, 0, 0));
            assertThat(peer.awaitClose()).isTrue();
        }
        try (Conversation peer = handshake()) {
            peer.send(rawFrame(1, MessageType.REQUEST.id(), 0, requestPayload(0, 0, BLOCK_SIZE)));
            assertThat(peer.awaitClose()).isTrue();
        }
        assertSeederStillServes();
    }

    @Test
    void aBitfieldThatDoesNotMatchTheAssetClosesTheConnection() throws Exception {
        try (Conversation peer = openAndHello()) {
            // Right shape, wrong asset geometry.
            peer.send(Frames.encode(Messages.bitfield(CHUNKS + 8, new byte[2], 0).frame()));

            assertThat(peer.awaitClose()).isTrue();
        }
        assertSeederStillServes();
    }

    @Test
    void aBitfieldWithPaddingBitsSetClosesTheConnection() throws Exception {
        try (Conversation peer = openAndHello()) {
            peer.send(Frames.encode(Messages.bitfield(CHUNKS, new byte[] {(byte) 0xFF}, 0).frame()));

            assertThat(peer.awaitClose()).isTrue();
        }
        assertSeederStillServes();
    }

    @Test
    void sendingABlockToASeederClosesTheConnection() throws Exception {
        try (Conversation peer = handshake()) {
            peer.send(Frames.encode(Messages.block(0, 0, new byte[64], REQUEST_ID).frame()));

            assertThat(peer.awaitClose()).isTrue();
        }
        assertSeederStillServes();
    }

    @Test
    void aHelloAckFromALeecherClosesTheConnection() throws Exception {
        try (Conversation peer = handshake()) {
            peer.send(Frames.encode(Messages.helloAck(true, BLOCK_SIZE, 0, 1).frame()));

            assertThat(peer.awaitClose()).isTrue();
        }
        assertSeederStillServes();
    }

    @Test
    void announcedFramesThatNeverArriveDoNotStarveTheSeeder() throws Exception {
        try (Conversation peer = handshake()) {
            byte[] header = bytes(0x00, 0x0F, 0xFF, 0xFC, 0x01, 0x04, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            for (int i = 0; i < 200; i++) {
                peer.send(header);
            }

            // The seeder is waiting for payload that will never come, not allocating for it.
            assertThat(peer.closedWithin(Duration.ofMillis(300))).isFalse();
        }
        assertSeederStillServes();
    }

    @Test
    void randomNoiseIsAlwaysRefusedAndNeverTakesTheSeederDown() throws Exception {
        for (int seed = 0; seed < 50; seed++) {
            Random random = new Random(seed);
            byte[] noise = new byte[16 + random.nextInt(512)];
            random.nextBytes(noise);

            try (Conversation peer = open()) {
                peer.send(noise);
                peer.closedWithin(Duration.ofMillis(200));
            }
        }
        assertSeederStillServes();
    }

    /** The whole point of closing rudely: the next honest peer still gets served. */
    private void assertSeederStillServes() throws Exception {
        ChunkStore leecherStore = new ChunkStore(tempDir.resolve("leecher-" + System.nanoTime()));
        ChunkInventory inventory = new ChunkInventory(manifest, leecherStore);
        try (LeecherClient client = new LeecherClient();
             ChunkAssembler assembler = new ChunkAssembler(inventory, leecherStore,
                     tempDir.resolve("leecher-staging-" + System.nanoTime()))) {

            LeecherHandler.Result result = client.fetch(server.address(), new LeecherClient.Request(
                            assetId, PeerId.of(filled((byte) 2, 16)),
                            "token".getBytes(StandardCharsets.US_ASCII), inventory, assembler,
                            new LeecherHandler.Settings(BLOCK_SIZE, 4, Duration.ofSeconds(5), 3),
                            Duration.ofSeconds(5)))
                    .get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

            assertThat(result.chunksStored()).isEqualTo(CHUNKS);
            assertThat(inventory.complete()).isTrue();
        }
    }

    private void assertClosedAfter(byte[] payload) throws Exception {
        try (Conversation peer = open()) {
            peer.send(payload);

            assertThat(peer.awaitClose()).isTrue();
        }
        assertSeederStillServes();
    }

    private Conversation open() throws IOException {
        return new Conversation(server.address().getPort());
    }

    /** Connected, HELLO sent, HELLO_ACK and BITFIELD read back. */
    private Conversation openAndHello() throws Exception {
        Conversation peer = open();
        peer.send(Frames.encode(hello()));
        peer.readFrame();
        peer.readFrame();
        return peer;
    }

    /** Fully ACTIVE from the seeder's point of view. */
    private Conversation handshake() throws Exception {
        Conversation peer = openAndHello();
        peer.send(Frames.encode(Messages.bitfield(CHUNKS, new byte[1], 0).frame()));
        return peer;
    }

    private PeerFrame hello() {
        return Messages.hello(assetId, PeerId.of(filled((byte) 3, 16)),
                "token".getBytes(StandardCharsets.US_ASCII), 0, 1).frame();
    }

    private static byte[] rawRequest(long chunkIndex, long blockOffset, long blockLength) {
        return rawFrame(1, MessageType.REQUEST.id(), REQUEST_ID,
                requestPayload(chunkIndex, blockOffset, blockLength));
    }

    private static byte[] requestPayload(long chunkIndex, long blockOffset, long blockLength) {
        ByteBuf buf = Unpooled.buffer(12);
        buf.writeInt((int) chunkIndex);
        buf.writeInt((int) blockOffset);
        buf.writeInt((int) blockLength);
        return drainBuffer(buf);
    }

    /** Frame builder that accepts values {@code Frames.encode} would refuse. */
    private static byte[] rawFrame(int version, byte type, long requestId, byte[] payload) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(ProtocolLimits.HEADER_AFTER_LENGTH + payload.length);
        buf.writeByte(version);
        buf.writeByte(type);
        buf.writeShort(0);
        buf.writeLong(requestId);
        buf.writeBytes(payload);
        return drainBuffer(buf);
    }

    private static byte[] drainBuffer(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] filled(byte value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, value);
        return out;
    }

    private static byte[] deterministicBytes(int length) {
        byte[] out = new byte[length];
        new Random(20260913).nextBytes(out);
        return out;
    }

    /** A hand-driven peer: writes exactly what the test says and watches for the close. */
    private static final class Conversation implements AutoCloseable {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Conversation(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setTcpNoDelay(true);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        void send(byte[] bytes) throws IOException {
            try {
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                // The seeder may have closed already; that is the outcome under test.
            }
        }

        PeerFrame readFrame() throws IOException {
            byte[] lengthBytes = readFully(4);
            int frameLength = Unpooled.wrappedBuffer(lengthBytes).readInt();
            byte[] rest = readFully(frameLength);
            ByteBuf buf = Unpooled.wrappedBuffer(rest);
            byte version = buf.readByte();
            MessageType type = MessageType.fromId(buf.readByte());
            int flags = buf.readUnsignedShort();
            long requestId = buf.readLong();
            byte[] payload = new byte[buf.readableBytes()];
            buf.readBytes(payload);
            return new PeerFrame(version, type, flags, requestId, payload);
        }

        boolean awaitClose() throws IOException {
            return closedWithin(PATIENCE);
        }

        /** Drains anything the seeder still wants to say, then waits for end of stream. */
        boolean closedWithin(Duration limit) throws IOException {
            socket.setSoTimeout((int) limit.toMillis());
            byte[] scratch = new byte[4_096];
            try {
                while (true) {
                    if (in.read(scratch) < 0) {
                        return true;
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                return false;
            } catch (IOException e) {
                // A reset counts as closed: the seeder dropped us.
                return true;
            }
        }

        private byte[] readFully(int count) throws IOException {
            socket.setSoTimeout((int) PATIENCE.toMillis());
            byte[] bytes = new byte[count];
            int read = 0;
            while (read < count) {
                int step = in.read(bytes, read, count - read);
                if (step < 0) {
                    throw new IOException("stream ended after " + read + " of " + count + " bytes");
                }
                read += step;
            }
            return bytes;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
