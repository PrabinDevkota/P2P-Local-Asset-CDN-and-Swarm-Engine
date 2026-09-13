package com.prabin.swarmedge.peer.session;

import com.prabin.swarmedge.common.Defaults;
import com.prabin.swarmedge.protocol.MessageType;
import com.prabin.swarmedge.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultFileRegion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Writes a BLOCK as metadata followed by payload (blueprint §7.3, §7.4).
 *
 * <p>{@link Mode#FILE_REGION} hands the payload to the kernel as a region of the
 * verified chunk file, so the bytes never enter the JVM heap on the way out.
 * {@link Mode#BUFFERED} reads the span and writes it as an ordinary buffer, which the
 * blueprint keeps as the portable fallback because {@code transferTo} is not equally
 * willing on every platform.
 *
 * <p>The file length is checked before the header goes out. That ordering matters more
 * than it looks: the header promises exactly {@code blockLength} bytes, so discovering
 * a short file afterwards would leave the peer waiting for payload that does not exist
 * and desynchronize every frame after it.
 *
 * <p>Both modes touch the filesystem, so call this from a disk executor rather than the
 * event loop. Channel writes are thread-safe, so the write itself still lands in order.
 */
public final class BlockSender {

    public enum Mode {
        FILE_REGION,
        BUFFERED
    }

    private static final int HEADER_BYTES =
            Integer.BYTES + ProtocolLimits.HEADER_AFTER_LENGTH + ProtocolLimits.BLOCK_META_BYTES;

    private final Mode mode;

    public BlockSender(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public Mode mode() {
        return mode;
    }

    /**
     * @param chunkFile   a verified chunk in the content-addressed store
     * @param blockOffset offset inside the chunk, which is also the offset in the file
     */
    public ChannelFuture send(Channel channel, Path chunkFile, long requestId,
                              int chunkIndex, int blockOffset, int blockLength) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(chunkFile, "chunkFile");
        if (requestId == 0) {
            throw new IllegalArgumentException("BLOCK must answer a non-zero requestId");
        }
        if (blockOffset < 0 || blockLength <= 0 || blockLength > ProtocolLimits.absoluteMaxBlockSize()) {
            throw new IllegalArgumentException("illegal block span: " + blockOffset + "+" + blockLength);
        }
        long fileSize = Files.size(chunkFile);
        if ((long) blockOffset + blockLength > fileSize) {
            throw new IOException("block span leaves the chunk file: " + blockOffset + "+" + blockLength
                    + " > " + fileSize + " (" + chunkFile + ")");
        }

        // Build the payload first: once the header is queued, the frame is a promise.
        Object payload = mode == Mode.BUFFERED
                ? readPayload(channel, chunkFile, blockOffset, blockLength)
                : new DefaultFileRegion(chunkFile.toFile(), blockOffset, blockLength);

        channel.write(header(channel, requestId, chunkIndex, blockOffset, blockLength));
        ChannelFuture sent = channel.write(payload);
        channel.flush();
        return sent;
    }

    private static ByteBuf header(Channel channel, long requestId,
                                  int chunkIndex, int blockOffset, int blockLength) {
        int frameLength = ProtocolLimits.HEADER_AFTER_LENGTH + ProtocolLimits.BLOCK_META_BYTES + blockLength;
        ByteBuf header = channel.alloc().buffer(HEADER_BYTES, HEADER_BYTES);
        header.writeInt(frameLength);
        header.writeByte(Defaults.PROTOCOL_VERSION);
        header.writeByte(MessageType.BLOCK.id());
        header.writeShort(0);
        header.writeLong(requestId);
        header.writeInt(chunkIndex);
        header.writeInt(blockOffset);
        header.writeInt(blockLength);
        return header;
    }

    private static ByteBuf readPayload(Channel channel, Path chunkFile, int blockOffset, int blockLength)
            throws IOException {
        ByteBuf payload = channel.alloc().buffer(blockLength, blockLength);
        try (FileChannel file = FileChannel.open(chunkFile)) {
            ByteBuffer target = ByteBuffer.allocate(blockLength);
            long position = blockOffset;
            while (target.hasRemaining()) {
                int read = file.read(target, position);
                if (read <= 0) {
                    throw new IOException("chunk file ended early at offset " + position);
                }
                position += read;
            }
            payload.writeBytes(target.flip());
            return payload;
        } catch (IOException | RuntimeException e) {
            payload.release();
            throw e;
        }
    }
}
