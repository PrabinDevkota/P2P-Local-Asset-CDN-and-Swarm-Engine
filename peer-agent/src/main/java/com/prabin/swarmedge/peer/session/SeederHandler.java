package com.prabin.swarmedge.peer.session;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.protocol.PeerFrame;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import com.prabin.swarmedge.protocol.msg.Messages;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serves verified chunks to one connected leecher (blueprint §7.2 to §7.5).
 *
 * <p>A REQUEST is answered only if it passes three gates: the session is ACTIVE, the
 * span sits inside a chunk the manifest describes, and we actually hold that chunk.
 * Anything outside the first two is a protocol violation and closes the connection;
 * a chunk we simply do not have is answered with ERROR, because that is a normal fact
 * about a swarm rather than misbehaviour.
 *
 * <p>Reads and sends happen on a disk executor, never on the event loop. Requests wait
 * in a bounded queue and are only handed to the executor while the channel is writable,
 * so a slow reader throttles us instead of filling our memory (blueprint §12.3).
 *
 * <p>A connection that never finishes its handshake is dropped on a deadline. Without
 * it, opening sockets and saying nothing would be enough to tie up a seeder for as long
 * as the operating system kept the connections alive.
 */
public final class SeederHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger log = LoggerFactory.getLogger(SeederHandler.class);

    /** ERROR code for a request we cannot answer from cache. */
    public static final int CHUNK_UNAVAILABLE = 1;
    /** ERROR code for a request that arrived while the send queue was full. */
    public static final int BUSY = 2;

    private static final int MAX_QUEUED_REQUESTS = 32;
    private static final int MAX_CONCURRENT_SENDS = 2;

    private final AssetId assetId;
    private final ChunkInventory inventory;
    private final ChunkStore store;
    private final BlockSender sender;
    private final Executor diskExecutor;
    private final PeerAuthPolicy authPolicy;
    private final Duration handshakeTimeout;
    private final PeerSession session;

    private final Deque<Pending> queue = new ArrayDeque<>();
    private final AtomicLong blocksSent = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong requestsCancelledBeforeSend = new AtomicLong();
    private final AtomicLong errorsSent = new AtomicLong();

    private int inFlight;
    private ScheduledFuture<?> handshakeDeadline;

    public SeederHandler(AssetId assetId, PeerId localPeerId, ChunkInventory inventory, ChunkStore store,
                         BlockSender sender, Executor diskExecutor, PeerAuthPolicy authPolicy,
                         int preferredMaxBlockSize, Duration handshakeTimeout) {
        this.assetId = Objects.requireNonNull(assetId, "assetId");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.store = Objects.requireNonNull(store, "store");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.diskExecutor = Objects.requireNonNull(diskExecutor, "diskExecutor");
        this.authPolicy = Objects.requireNonNull(authPolicy, "authPolicy");
        this.handshakeTimeout = Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        if (handshakeTimeout.isNegative() || handshakeTimeout.isZero()) {
            throw new IllegalArgumentException("handshakeTimeout must be positive");
        }
        this.session = new PeerSession(PeerSession.Role.SEEDER, assetId, localPeerId,
                inventory.chunkCount(), preferredMaxBlockSize);
    }

    public SessionState state() {
        return session.state();
    }

    public long blocksSent() {
        return blocksSent.get();
    }

    public long bytesSent() {
        return bytesSent.get();
    }

    public long requestsCancelledBeforeSend() {
        return requestsCancelledBeforeSend.get();
    }

    public long errorsSent() {
        return errorsSent.get();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        session.transitionTo(SessionState.TCP_CONNECTED);
        handshakeDeadline = ctx.executor().schedule(() -> {
            if (!session.state().transfersData()) {
                log.debug("dropping {}: handshake stalled in {}", ctx.channel().remoteAddress(), session.state());
                ctx.close();
            }
        }, handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof PeerFrame frame)) {
            // A seeder never requests anything, so inbound payload is not ours to take.
            throw new ProtocolViolationException("unexpected BLOCK payload on a seeder session");
        }
        switch (frame.type()) {
            case HELLO -> onHello(ctx, frame);
            case BITFIELD -> onBitfield(frame);
            case REQUEST -> onRequest(ctx, frame);
            case CANCEL -> onCancel(frame);
            // Everything below is conversation between two peers that have already
            // agreed who they are. Answering a stranger's PING would make an
            // unauthenticated socket useful, which is the thing to avoid.
            case PING -> {
                session.requireActive(frame.type());
                ctx.writeAndFlush(Messages.pong(Messages.decodePing(frame).nonce()).frame());
            }
            case HAVE -> {
                session.requireActive(frame.type());
                session.noteRemoteHas(Messages.decodeHave(frame).chunkIndex());
            }
            case PONG -> {
                session.requireActive(frame.type());
                Messages.decodePong(frame);
            }
            case ERROR -> onRemoteError(ctx, frame);
            case HELLO_ACK, BLOCK ->
                    throw new ProtocolViolationException(frame.type() + " is not something a seeder receives");
        }
    }

    private void onHello(ChannelHandlerContext ctx, PeerFrame frame) {
        session.require(SessionState.TCP_CONNECTED, frame.type());
        Messages.Hello hello = Messages.decodeHello(frame);
        session.transitionTo(SessionState.HELLO_RECEIVED);

        if (!hello.assetId().equals(assetId)) {
            refuse(ctx, frame.requestId(), PeerAuthPolicy.UNKNOWN_ASSET);
            return;
        }
        int reason = authPolicy.check(hello.assetId(), hello.peerId(), hello.token());
        if (reason != PeerAuthPolicy.ACCEPTED) {
            refuse(ctx, frame.requestId(), reason);
            return;
        }

        session.remotePeerId(hello.peerId());
        // We advertise our own ceiling; the leecher applies the smaller of the two.
        ctx.writeAndFlush(Messages.helloAck(true, session.preferredMaxBlockSize(),
                PeerAuthPolicy.ACCEPTED, frame.requestId()).frame());
        session.transitionTo(SessionState.AUTHENTICATED);
        sendBitfield(ctx);
    }

    private void refuse(ChannelHandlerContext ctx, long requestId, int reason) {
        log.debug("refusing session on {}: reason {}", ctx.channel().remoteAddress(), reason);
        ctx.writeAndFlush(Messages.helloAck(false, session.preferredMaxBlockSize(), reason, requestId).frame())
                .addListener(ChannelFutureListener.CLOSE);
        session.transitionTo(SessionState.CLOSED);
    }

    private void sendBitfield(ChannelHandlerContext ctx) {
        ctx.writeAndFlush(Messages.bitfield(inventory.chunkCount(), inventory.bitfield(), 0).frame());
        session.noteBitfieldSent();
        activateIfReady();
    }

    private void onBitfield(PeerFrame frame) {
        session.require(SessionState.AUTHENTICATED, frame.type());
        Messages.Bitfield bitfield = Messages.decodeBitfield(frame);
        session.acceptRemoteBitfield(bitfield.bitCount(), bitfield.bits());
        activateIfReady();
    }

    private void activateIfReady() {
        if (session.activateIfBitfieldsExchanged()) {
            cancelHandshakeDeadline();
        }
    }

    private void onRequest(ChannelHandlerContext ctx, PeerFrame frame) {
        session.requireActive(frame.type());
        Messages.Request request = Messages.decodeRequest(frame);
        if (frame.requestId() == 0) {
            throw new ProtocolViolationException("REQUEST needs a non-zero requestId");
        }
        if (request.blockLength() > session.maxBlockSize()) {
            throw new ProtocolViolationException("REQUEST exceeds the advertised block size: "
                    + request.blockLength() + " > " + session.maxBlockSize());
        }
        ChunkEntry chunk = inventory.requireInRange(
                request.chunkIndex(), request.blockOffset(), request.blockLength());

        if (!inventory.has(request.chunkIndex())) {
            sendError(ctx, CHUNK_UNAVAILABLE, "chunk " + request.chunkIndex() + " not cached", frame.requestId());
            return;
        }
        if (queue.size() >= MAX_QUEUED_REQUESTS) {
            sendError(ctx, BUSY, "send queue is full", frame.requestId());
            return;
        }
        queue.addLast(new Pending(frame.requestId(), request.chunkIndex(),
                request.blockOffset(), request.blockLength(), chunk.sha256()));
        pump(ctx);
    }

    private void onCancel(PeerFrame frame) {
        long requestId = Messages.decodeCancel(frame).requestId();
        // Work already handed to the disk executor cannot be recalled; queued work can.
        if (queue.removeIf(pending -> pending.requestId() == requestId)) {
            requestsCancelledBeforeSend.incrementAndGet();
        }
    }

    private void onRemoteError(ChannelHandlerContext ctx, PeerFrame frame) {
        Messages.ErrorMessage error = Messages.decodeError(frame);
        log.debug("leecher reported error {}: {}", error.code(), error.diagnostic());
        session.transitionTo(SessionState.CLOSED);
        ctx.close();
    }

    private void sendError(ChannelHandlerContext ctx, int code, String diagnostic, long requestId) {
        errorsSent.incrementAndGet();
        ctx.writeAndFlush(Messages.error(code, diagnostic, requestId).frame());
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        pump(ctx);
        ctx.fireChannelWritabilityChanged();
    }

    /** Hand queued requests to the disk executor while the socket is willing to take more. */
    private void pump(ChannelHandlerContext ctx) {
        while (inFlight < MAX_CONCURRENT_SENDS && !queue.isEmpty() && ctx.channel().isWritable()) {
            Pending pending = queue.pollFirst();
            inFlight++;
            diskExecutor.execute(() -> {
                try {
                    sender.send(ctx.channel(), store.pathFor(pending.sha256()), pending.requestId(),
                            pending.chunkIndex(), pending.blockOffset(), pending.blockLength());
                    blocksSent.incrementAndGet();
                    bytesSent.addAndGet(pending.blockLength());
                } catch (Exception e) {
                    // The chunk went away between the bounds check and the read, so the
                    // honest answer is that we cannot serve it rather than a short frame.
                    log.warn("failed to serve chunk {} [{}+{}]: {}", pending.chunkIndex(),
                            pending.blockOffset(), pending.blockLength(), e.toString());
                    errorsSent.incrementAndGet();
                    ctx.channel().writeAndFlush(
                            Messages.error(CHUNK_UNAVAILABLE, "read failed", pending.requestId()).frame());
                } finally {
                    ctx.channel().eventLoop().execute(() -> {
                        inFlight--;
                        pump(ctx);
                    });
                }
            });
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelHandshakeDeadline();
        queue.clear();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (isViolation(cause)) {
            session.noteViolation();
            log.debug("closing session on {} after protocol violation: {}",
                    ctx.channel().remoteAddress(), cause.toString());
        } else {
            log.warn("closing session on {}: {}", ctx.channel().remoteAddress(), cause.toString());
        }
        cancelHandshakeDeadline();
        queue.clear();
        if (!session.state().isTerminal()) {
            session.transitionTo(SessionState.CLOSED);
        }
        ctx.close();
    }

    private void cancelHandshakeDeadline() {
        if (handshakeDeadline != null) {
            handshakeDeadline.cancel(false);
            handshakeDeadline = null;
        }
    }

    private static boolean isViolation(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof ProtocolViolationException) {
                return true;
            }
        }
        return false;
    }

    private record Pending(long requestId, int chunkIndex, int blockOffset, int blockLength, String sha256) {
    }
}
