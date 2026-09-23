package com.prabin.swarmedge.peer.session;

import com.prabin.swarmedge.common.Defaults;
import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.peer.chunk.BlockPlan;
import com.prabin.swarmedge.peer.chunk.BlockSource;
import com.prabin.swarmedge.peer.chunk.ChunkAssembler;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.protocol.MessageType;
import com.prabin.swarmedge.protocol.PeerFrame;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import com.prabin.swarmedge.protocol.codec.BlockStream;
import com.prabin.swarmedge.protocol.msg.Messages;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Fetches one asset from one seeder (blueprint §7.2 to §7.5, §9.2).
 *
 * <p>The receive path is deliberately boring: a BLOCK header is matched against a
 * request we issued, its payload is written straight to the chunk's staging file as it
 * arrives, and the chunk only becomes real when its own SHA-256 matches the signed
 * manifest. The peer on the other end is never trusted for anything except bytes.
 *
 * <p>Requests are kept within the tracker's budget, which bounds both how much the peer
 * can have in flight and how much of it we are willing to hold. A block that does not
 * arrive in time is cancelled and re-queued. A chunk whose hash is wrong is thrown
 * away and rebuilt: with a single peer that ends the session, because there is nowhere
 * else to ask, and in a swarm the scheduler puts the chunk back on the shared queue
 * so another peer can supply it. The session that delivered the last block is not
 * blamed — in a swarm that block is only one slice of a chunk many peers wrote.
 *
 * <p>A seeder that accepts the connection and then says nothing would otherwise leave
 * the transfer waiting forever, because the block timeout only starts once blocks are
 * being requested. The handshake therefore has a deadline of its own.
 *
 * <p>All disk work runs on {@code diskExecutor}. It must be single-threaded: block
 * writes and the commit that follows them are ordered by submission, not by locking.
 */
public final class LeecherHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger log = LoggerFactory.getLogger(LeecherHandler.class);

    /** HELLO is a request/response pair, so it carries a non-zero id the ACK must echo. */
    private static final long HELLO_REQUEST_ID = 1L;

    private final AssetId assetId;
    private final byte[] token;
    private final ChunkInventory inventory;
    private final ChunkAssembler assembler;
    private final Executor diskExecutor;
    private final Settings settings;
    private final BlockSource.Factory sources;
    private final SessionEvents events;
    private final PeerSession session;
    private final RequestTracker tracker;
    private final PipelineWindow window;
    private final CompletableFuture<Result> completion = new CompletableFuture<>();
    private final Map<BlockPlan.Block, Integer> attempts = new HashMap<>();

    private BlockSource plan;
    private InFlight current;
    private int pendingCommits;
    private ScheduledFuture<?> sweep;
    private ScheduledFuture<?> handshakeDeadline;
    private ChannelHandlerContext context;

    private long bytesReceived;
    private int blocksRequested;
    private int chunksStored;
    private int hashMismatches;
    private int blocksIgnoredAsLate;
    private int duplicatesCancelled;

    /** One peer, one queue: this session is the only source of what is missing. */
    public LeecherHandler(AssetId assetId, PeerId localPeerId, byte[] token, ChunkInventory inventory,
                          ChunkAssembler assembler, Executor diskExecutor, Settings settings) {
        this(assetId, localPeerId, token, inventory, assembler, diskExecutor, settings,
                negotiated -> new BlockPlan(inventory, negotiated), SessionEvents.NONE);
    }

    public LeecherHandler(AssetId assetId, PeerId localPeerId, byte[] token, ChunkInventory inventory,
                          ChunkAssembler assembler, Executor diskExecutor, Settings settings,
                          BlockSource.Factory sources, SessionEvents events) {
        this.assetId = Objects.requireNonNull(assetId, "assetId");
        this.token = Objects.requireNonNull(token, "token").clone();
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.diskExecutor = Objects.requireNonNull(diskExecutor, "diskExecutor");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.events = Objects.requireNonNull(events, "events");
        this.window = settings.window();
        this.session = new PeerSession(PeerSession.Role.LEECHER, assetId, localPeerId,
                inventory.chunkCount(), settings.blockSize());
        this.tracker = new RequestTracker(settings.maxOutstanding(),
                (long) settings.maxOutstanding() * settings.blockSize(),
                settings.blockTimeout(), System::nanoTime);
    }

    /** Completes when the asset is fully verified, or fails closed with the reason. */
    public CompletableFuture<Result> completion() {
        return completion;
    }

    public SessionState state() {
        return session.state();
    }

    public long blocksIgnoredAsLate() {
        return blocksIgnoredAsLate;
    }

    /** Endgame duplicates this session was told to stop fetching (P6-04). */
    public int duplicatesCancelled() {
        return duplicatesCancelled;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        context = ctx;
        session.transitionTo(SessionState.TCP_CONNECTED);
        ctx.writeAndFlush(Messages.hello(assetId, session.localPeerId(), token, 0, HELLO_REQUEST_ID).frame());
        session.transitionTo(SessionState.HELLO_SENT);
        handshakeDeadline = ctx.executor().schedule(() -> {
            if (!session.state().transfersData()) {
                failAndClose(ctx, new IOException(
                        "the seeder did not finish the handshake within " + settings.handshakeTimeout()
                                + ", stalled in " + session.state()));
            }
        }, settings.handshakeTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof PeerFrame frame) {
            onFrame(ctx, frame);
        } else if (msg instanceof BlockStream.Begin begin) {
            onBlockBegin(begin);
        } else if (msg instanceof BlockStream.Data data) {
            onBlockData(ctx, data);
        } else if (msg instanceof BlockStream.End end) {
            onBlockEnd(ctx, end);
        } else {
            throw new ProtocolViolationException("unexpected inbound message: " + msg.getClass());
        }
    }

    private void onFrame(ChannelHandlerContext ctx, PeerFrame frame) {
        switch (frame.type()) {
            case HELLO_ACK -> onHelloAck(ctx, frame);
            case BITFIELD -> onBitfield(ctx, frame);
            case HAVE -> onHave(ctx, frame);
            case PING -> ctx.writeAndFlush(Messages.pong(Messages.decodePing(frame).nonce()).frame());
            case PONG -> Messages.decodePong(frame);
            case ERROR -> onRemoteError(ctx, frame);
            case HELLO, REQUEST, CANCEL, BLOCK ->
                    throw new ProtocolViolationException(frame.type() + " is not something a leecher receives");
        }
    }

    private void onHelloAck(ChannelHandlerContext ctx, PeerFrame frame) {
        session.require(SessionState.HELLO_SENT, frame.type());
        if (frame.requestId() != HELLO_REQUEST_ID) {
            throw new ProtocolViolationException("HELLO_ACK does not answer our HELLO: " + frame.requestId());
        }
        Messages.HelloAck ack = Messages.decodeHelloAck(frame);
        if (!ack.accepted()) {
            failAndClose(ctx, new IOException("seeder refused the session, reason " + ack.reasonCode()));
            return;
        }
        session.negotiate(ack.maxBlockSize());
        session.transitionTo(SessionState.AUTHENTICATED);

        // The source is built after negotiation: asking in blocks the peer refuses to
        // serve would be a guaranteed protocol violation on the very first REQUEST. A
        // swarm may refuse the peer outright here, because a shared queue only works if
        // every session cuts chunks the same way.
        try {
            plan = sources.create(session.maxBlockSize());
        } catch (RuntimeException e) {
            failAndClose(ctx, new IOException("cannot fetch from this peer: " + e.getMessage(), e));
            return;
        }
        sendBitfield(ctx);
    }

    private void sendBitfield(ChannelHandlerContext ctx) {
        ctx.writeAndFlush(Messages.bitfield(inventory.chunkCount(), inventory.bitfield(), 0).frame());
        session.noteBitfieldSent();
        activateIfReady(ctx);
    }

    private void onBitfield(ChannelHandlerContext ctx, PeerFrame frame) {
        session.require(SessionState.AUTHENTICATED, frame.type());
        Messages.Bitfield bitfield = Messages.decodeBitfield(frame);
        session.acceptRemoteBitfield(bitfield.bitCount(), bitfield.bits());
        events.remoteInventory(bitfield.bits());
        activateIfReady(ctx);
    }

    private void activateIfReady(ChannelHandlerContext ctx) {
        if (!session.activateIfBitfieldsExchanged()) {
            return;
        }
        cancelHandshakeDeadline();
        long interval = Math.max(1, settings.blockTimeout().toMillis() / 2);
        sweep = ctx.executor().scheduleAtFixedRate(
                () -> onTimeoutSweep(ctx), interval, interval, TimeUnit.MILLISECONDS);
        requestMore(ctx);
    }

    private void onHave(ChannelHandlerContext ctx, PeerFrame frame) {
        session.requireActive(frame.type());
        int chunkIndex = Messages.decodeHave(frame).chunkIndex();
        session.noteRemoteHas(chunkIndex);
        events.remoteGained(chunkIndex);
        requestMore(ctx);
    }

    /**
     * End this session from outside, for example because the asset is now complete or
     * the swarm is shutting down. Safe to call from any thread.
     */
    public void stop() {
        ChannelHandlerContext ctx = context;
        if (ctx == null) {
            completion.completeExceptionally(new IOException("session stopped before it connected"));
            return;
        }
        onEventLoop(ctx, ctx::close);
    }

    /**
     * Stop fetching a block another peer already delivered (P6-04, §8.3).
     *
     * <p>Called from the winning session's thread, so the work hops onto ours. A block
     * whose payload is already arriving is left alone unless {@code abandon} is set:
     * those bytes are about to land at the same offset with the same contents, and
     * abandoning them mid-stream would cost more than it saves. A hash-fail rebuild
     * is the exception — those bytes belong to a round that already failed.
     */
    public void cancelBlock(BlockPlan.Block block) {
        cancelBlock(block, false);
    }

    public void cancelBlock(BlockPlan.Block block, boolean abandon) {
        Objects.requireNonNull(block, "block");
        ChannelHandlerContext ctx = context;
        if (ctx == null) {
            return;
        }
        onEventLoop(ctx, () -> {
            if (!session.state().transfersData()) {
                return;
            }
            InFlight arriving = current;
            boolean thisBlock = arriving != null && arriving.chunkIndex() == block.chunkIndex()
                    && arriving.blockOffset() == block.blockOffset();
            if (thisBlock && !abandon) {
                return;
            }
            if (thisBlock) {
                current = new InFlight(arriving.chunkIndex(), arriving.blockOffset(), false, arriving.issuedAtNanos());
            }
            tracker.findBySpan(block.chunkIndex(), block.blockOffset(), block.blockLength())
                    .ifPresent(request -> {
                        tracker.cancel(request.requestId());
                        ctx.writeAndFlush(Messages.cancel(request.requestId()).frame());
                        duplicatesCancelled++;
                        // The budget just freed up, so ask for something still wanted.
                        requestMore(ctx);
                    });
        });
    }

    /**
     * Tell this peer we finished a chunk, so it can ask us for it. Safe to call from
     * another session's thread; the write is hopped onto this one.
     */
    public void announceHave(int chunkIndex) {
        ChannelHandlerContext ctx = context;
        if (ctx == null) {
            return;
        }
        onEventLoop(ctx, () -> {
            if (session.state().transfersData()) {
                ctx.writeAndFlush(Messages.have(chunkIndex).frame());
            }
        });
    }

    private void onRemoteError(ChannelHandlerContext ctx, PeerFrame frame) {
        Messages.ErrorMessage error = Messages.decodeError(frame);
        long requestId = frame.requestId();
        Optional<RequestTracker.Outstanding> refused = tracker.cancel(requestId);
        if (refused.isEmpty()) {
            failAndClose(ctx, new IOException("seeder reported error " + error.code() + ": " + error.diagnostic()));
            return;
        }
        // The peer cannot serve this block right now; put it back and move on.
        log.debug("seeder refused block {}: {}", requestId, error.diagnostic());
        RequestTracker.Outstanding request = refused.get();
        BlockPlan.Block block = blockOf(request);
        if (countAttempt(ctx, block, "refused")) {
            events.blockFailed();
            noteWindow(false);
            plan.requeue(block);
            requestMore(ctx);
        }
    }

    private void requestMore(ChannelHandlerContext ctx) {
        if (completion.isDone() || !session.state().transfersData()) {
            return;
        }
        while (true) {
            Optional<BlockPlan.Block> candidate = plan.next(session::remoteHas);
            if (candidate.isEmpty()) {
                break;
            }
            BlockPlan.Block block = candidate.get();
            if (!tracker.hasCapacityFor(block.blockLength())) {
                plan.requeue(block);
                break;
            }
            RequestTracker.Outstanding request =
                    tracker.issue(block.chunkIndex(), block.blockOffset(), block.blockLength());
            blocksRequested++;
            ctx.writeAndFlush(Messages.request(block.chunkIndex(), block.blockOffset(),
                    block.blockLength(), request.requestId()).frame());
        }
        // Only decide anything once nothing is in flight: a chunk still being hashed on
        // the disk thread may yet turn into progress, and acting on it now would throw
        // away bytes we already have.
        if (tracker.outstandingCount() > 0 || pendingCommits > 0) {
            return;
        }
        if (plan.isEmpty()) {
            succeed(ctx);
        } else if (plan.soleSource()) {
            failAndClose(ctx, new IOException(
                    "the peer does not hold the remaining " + plan.pendingBlocks() + " blocks"));
        }
        // Otherwise: idle. Other peers are working on the rest, and blocks they give up
        // come back to the shared queue, so the next sweep tries again.
    }

    private void onBlockBegin(BlockStream.Begin begin) {
        session.requireActive(MessageType.BLOCK);
        if (current != null) {
            throw new ProtocolViolationException("a BLOCK began before the previous one finished");
        }
        Optional<RequestTracker.Outstanding> matched = tracker.accept(
                begin.requestId(), begin.chunkIndex(), begin.blockOffset(), begin.blockLength());
        if (matched.isEmpty()) {
            // Cancelled or timed out: the bytes are late, so read past them and forget them.
            blocksIgnoredAsLate++;
            current = new InFlight(begin.chunkIndex(), begin.blockOffset(), false, 0L);
            return;
        }
        inventory.requireInRange(begin.chunkIndex(), begin.blockOffset(), begin.blockLength());
        current = new InFlight(begin.chunkIndex(), begin.blockOffset(), true, matched.get().issuedAtNanos());
    }

    private void onBlockData(ChannelHandlerContext ctx, BlockStream.Data data) {
        if (current == null) {
            throw new ProtocolViolationException("BLOCK payload arrived without a header");
        }
        if (!current.accepted()) {
            return;
        }
        long offsetInChunk = (long) current.blockOffset() + data.offsetInBlock();
        int chunkIndex = current.chunkIndex();
        byte[] bytes = data.bytes();
        // Capture the round before hopping to disk: a hash mismatch can bump the epoch
        // while this write is still queued, and those bytes must not seed the retry.
        int expectedEpoch = assembler.epoch(chunkIndex);
        onDisk(ctx, () -> assembler.accept(chunkIndex, offsetInChunk, bytes, 0, bytes.length,
                expectedEpoch));
    }

    private void onBlockEnd(ChannelHandlerContext ctx, BlockStream.End end) {
        InFlight finished = current;
        current = null;
        if (finished == null) {
            throw new ProtocolViolationException("BLOCK ended without a header");
        }
        if (!finished.accepted()) {
            return;
        }
        tracker.complete(end.requestId());
        bytesReceived += end.blockLength();
        events.blockCompleted(end.blockLength(),
                Duration.ofNanos(Math.max(0L, System.nanoTime() - finished.issuedAtNanos())));
        noteWindow(true);
        // These bytes are ours, so any second peer fetching the same block can stop.
        plan.completed(new BlockPlan.Block(
                finished.chunkIndex(), finished.blockOffset(), end.blockLength()));

        int chunkIndex = finished.chunkIndex();
        pendingCommits++;
        onDisk(ctx, () -> {
            Optional<Path> stored;
            try {
                stored = assembler.commitIfComplete(chunkIndex);
            } catch (ChunkAssembler.VerificationFailed e) {
                onEventLoop(ctx, () -> onChunkRejected(ctx, e));
                return;
            }
            boolean committed = stored.isPresent();
            onEventLoop(ctx, () -> onChunkSettled(ctx, chunkIndex, committed));
        });
    }

    private void onChunkSettled(ChannelHandlerContext ctx, int chunkIndex, boolean committed) {
        pendingCommits--;
        if (committed) {
            chunksStored++;
            // Now it is ours to serve, and the inventory is what a BITFIELD or HAVE reads.
            inventory.markStored(chunkIndex);
            plan.dropChunk(chunkIndex);
            events.chunkStored(chunkIndex);
        }
        requestMore(ctx);
    }

    private void onChunkRejected(ChannelHandlerContext ctx, ChunkAssembler.VerificationFailed failure) {
        pendingCommits--;
        hashMismatches++;
        events.hashMismatch();
        plan.requeueChunk(failure.chunkIndex());
        if (plan.soleSource()) {
            // One peer, one source of bytes: there is no better peer to ask, so stop.
            failAndClose(ctx, failure);
            return;
        }
        // A swarm assembled this chunk from many peers. The session that delivered the
        // last block is not the one that poisoned it, so killing it would drop an honest
        // source and keep the liar. Rebuild from the shared queue instead. After the
        // same number of mismatches as block attempts this session is itself a bad bet.
        if (hashMismatches >= settings.maxAttemptsPerBlock()) {
            failAndClose(ctx, failure);
            return;
        }
        requestMore(ctx);
    }

    private void onTimeoutSweep(ChannelHandlerContext ctx) {
        if (completion.isDone()) {
            return;
        }
        for (RequestTracker.Outstanding request : tracker.expire()) {
            ctx.writeAndFlush(Messages.cancel(request.requestId()).frame());
            BlockPlan.Block block = blockOf(request);
            if (!countAttempt(ctx, block, "timed out")) {
                return;
            }
            events.blockFailed();
            noteWindow(false);
            plan.requeue(block);
        }
        requestMore(ctx);
    }

    /** @return false when the block has run out of attempts and the session is now failing */
    private boolean countAttempt(ChannelHandlerContext ctx, BlockPlan.Block block, String why) {
        int tries = attempts.merge(block, 1, Integer::sum);
        if (tries >= settings.maxAttemptsPerBlock()) {
            failAndClose(ctx, new IOException("chunk " + block.chunkIndex() + " block "
                    + block.blockOffset() + " " + why + " " + tries + " times"));
            return false;
        }
        return true;
    }

    private void succeed(ChannelHandlerContext ctx) {
        if (completion.isDone()) {
            return;
        }
        stopSweep();
        session.transitionTo(SessionState.DRAINING);
        session.transitionTo(SessionState.CLOSED);
        completion.complete(new Result(chunksStored, bytesReceived, blocksRequested, hashMismatches));
        ctx.close();
    }

    private void failAndClose(ChannelHandlerContext ctx, Throwable cause) {
        stopSweep();
        if (!session.state().isTerminal()) {
            session.transitionTo(SessionState.CLOSED);
        }
        completion.completeExceptionally(cause);
        ctx.close();
    }

    private void noteWindow(boolean success) {
        if (window == null) {
            return;
        }
        int next = success ? window.onBlockCompleted() : window.onBlockFailed();
        tracker.setLimits(next, (long) next * settings.blockSize());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        stopSweep();
        if (plan != null) {
            // Whatever we were still holding is somebody else's to fetch now (P5-05).
            plan.surrender();
        }
        InFlight partial = current;
        current = null;
        // Only throw the staging file away when this session was the only one filling it.
        // In a swarm the same chunk is being built from several peers, so deleting it
        // would silently destroy blocks that other sessions have already delivered and
        // are no longer going to fetch again.
        if (partial != null && plan != null && plan.soleSource()) {
            int chunkIndex = partial.chunkIndex();
            diskExecutor.execute(() -> {
                try {
                    assembler.discard(chunkIndex);
                } catch (IOException e) {
                    log.debug("could not clean up staging for chunk {}: {}", chunkIndex, e.toString());
                }
            });
        }
        if (!completion.isDone()) {
            completion.completeExceptionally(new IOException("connection closed with "
                    + (plan == null ? "the handshake unfinished" : plan.pendingBlocks() + " blocks still owed")));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (isViolation(cause)) {
            session.noteViolation();
            log.debug("closing session after protocol violation: {}", cause.toString());
        } else {
            log.warn("closing session: {}", cause.toString());
        }
        failAndClose(ctx, cause);
    }

    private void stopSweep() {
        cancelHandshakeDeadline();
        if (sweep != null) {
            sweep.cancel(false);
            sweep = null;
        }
    }

    private void cancelHandshakeDeadline() {
        if (handshakeDeadline != null) {
            handshakeDeadline.cancel(false);
            handshakeDeadline = null;
        }
    }

    private void onDisk(ChannelHandlerContext ctx, DiskTask task) {
        diskExecutor.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                onEventLoop(ctx, () -> failAndClose(ctx, t));
            }
        });
    }

    private static void onEventLoop(ChannelHandlerContext ctx, Runnable action) {
        ctx.channel().eventLoop().execute(action);
    }

    private static BlockPlan.Block blockOf(RequestTracker.Outstanding request) {
        return new BlockPlan.Block(request.chunkIndex(), request.blockOffset(), request.blockLength());
    }

    private static boolean isViolation(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof ProtocolViolationException) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface DiskTask {
        void run() throws Exception;
    }

    private record InFlight(int chunkIndex, int blockOffset, boolean accepted, long issuedAtNanos) {
    }

    /**
     * @param blockSize          preferred request size; the negotiated ceiling may lower it
     * @param maxOutstanding     requests in flight, which is also the in-flight byte budget
     * @param blockTimeout       how long a block may take before it is cancelled and retried
     * @param maxAttemptsPerBlock attempts before the session gives up on the asset
     * @param handshakeTimeout   how long the seeder has to reach ACTIVE before we give up
     * @param window             adaptive outstanding cap, or null to stay at {@code maxOutstanding}
     */
    public record Settings(int blockSize, int maxOutstanding, Duration blockTimeout, int maxAttemptsPerBlock,
                           Duration handshakeTimeout, PipelineWindow window) {

        public Settings(int blockSize, int maxOutstanding, Duration blockTimeout, int maxAttemptsPerBlock,
                        Duration handshakeTimeout) {
            this(blockSize, maxOutstanding, blockTimeout, maxAttemptsPerBlock, handshakeTimeout, null);
        }

        public Settings {
            if (blockSize <= 0) {
                throw new IllegalArgumentException("blockSize must be positive");
            }
            if (maxOutstanding <= 0) {
                throw new IllegalArgumentException("maxOutstanding must be positive");
            }
            if (maxAttemptsPerBlock <= 0) {
                throw new IllegalArgumentException("maxAttemptsPerBlock must be positive");
            }
            Objects.requireNonNull(blockTimeout, "blockTimeout");
            Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
            if (handshakeTimeout.isNegative() || handshakeTimeout.isZero()) {
                throw new IllegalArgumentException("handshakeTimeout must be positive");
            }
        }

        public static Settings defaults() {
            return new Settings(Defaults.BLOCK_SIZE_BYTES, Defaults.OUTSTANDING_REQUESTS_PER_PEER,
                    Duration.ofSeconds(30), 3, Duration.ofSeconds(10));
        }

        /** Same numbers as {@link #defaults()}, with a window that may move after the baseline. */
        public static Settings adaptive() {
            return new Settings(Defaults.BLOCK_SIZE_BYTES, Defaults.OUTSTANDING_REQUESTS_PER_PEER,
                    Duration.ofSeconds(30), 3, Duration.ofSeconds(10), new PipelineWindow());
        }
    }

    public record Result(int chunksStored, long bytesReceived, int blocksRequested, int hashMismatches) {
    }
}
