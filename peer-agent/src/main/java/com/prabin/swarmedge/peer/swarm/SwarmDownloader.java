package com.prabin.swarmedge.peer.swarm;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.peer.chunk.BlockPlan;
import com.prabin.swarmedge.peer.chunk.ChunkAssembler;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.peer.net.LeecherClient;
import com.prabin.swarmedge.peer.session.LeecherHandler;
import com.prabin.swarmedge.peer.session.SessionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fetches one asset from several peers at once (blueprint Phase 5, baseline B1).
 *
 * <p>Everything the sessions must agree on lives here and is shared: one inventory, one
 * assembler, one {@link ChunkAvailability}, and one {@link SwarmScheduler}. A session on
 * its own decides nothing about what to fetch — it asks the scheduler — which is what
 * makes "never ask two peers for the same block" true rather than hopeful.
 *
 * <p>Three things happen as chunks land. The chunk is marked in the shared inventory, it
 * leaves the shared queue, and a HAVE goes out to every <em>other</em> session, so a peer
 * that needs it can ask us immediately (P5-01). A session that dies gives its blocks back
 * to the queue, and a spare candidate is dialled in its place, so the swarm keeps going
 * as long as the remaining peers still cover what is missing (P5-05).
 *
 * <p>The asset future completes when the queue is empty and every chunk has verified. It
 * fails when no session is left and the queue is not, and it fails on a stall deadline
 * when the peers we are connected to stop being able to supply what is missing — a swarm
 * with living peers that hold none of the remaining chunks would otherwise sit connected
 * and idle forever. Waiting until the deadline rather than the moment a chunk looks
 * unreachable is deliberate: a peer that is itself still downloading may announce that
 * chunk a second later, and cancelling a swarm for that would be wrong.
 */
public final class SwarmDownloader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SwarmDownloader.class);

    private final Settings settings;
    private final ChunkInventory inventory;
    private final ChunkAssembler assembler;
    private final ChunkAvailability availability;
    private final SwarmScheduler scheduler;
    private final LeecherClient client;
    private final CompletableFuture<Result> completion = new CompletableFuture<>();

    private final Map<Integer, LeecherHandler> sessions = new HashMap<>();
    private final Deque<InetSocketAddress> spares = new ArrayDeque<>();
    private final ScheduledExecutorService watchdog;

    private int nextSessionId = 1;
    private int sessionsStarted;
    private int sessionsLost;
    private boolean started;
    private long lastProgress;
    private ScheduledFuture<?> stallCheck;

    public SwarmDownloader(Settings settings, ChunkInventory inventory, ChunkAssembler assembler) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.availability = new ChunkAvailability(inventory.chunkCount(), settings.tieBreakSeed());
        this.scheduler = new SwarmScheduler(inventory, availability, settings.session().blockSize(),
                settings.endgameThreshold(), new SwarmScheduler.DuplicateCanceller() {
                    @Override
                    public void cancel(int sessionId, BlockPlan.Block block) {
                        cancelOn(sessionId, block, false);
                    }

                    @Override
                    public void abandon(int sessionId, BlockPlan.Block block) {
                        cancelOn(sessionId, block, true);
                    }
                });
        this.client = new LeecherClient();
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "swarm-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Dial up to {@code maxPeers} of the candidates and start fetching. Any candidates
     * beyond that are kept as replacements for peers that drop.
     */
    public synchronized CompletableFuture<Result> start(List<InetSocketAddress> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (started) {
            throw new IllegalStateException("this swarm has already been started");
        }
        started = true;

        if (scheduler.isDone()) {
            // Nothing missing, so there is nothing to dial for.
            finish();
            return completion;
        }
        if (candidates.isEmpty()) {
            completion.completeExceptionally(new IOException("no candidate peers to fetch from"));
            return completion;
        }

        List<InetSocketAddress> toDial = candidates.size() <= settings.maxPeers()
                ? List.copyOf(candidates)
                : List.copyOf(candidates.subList(0, settings.maxPeers()));
        spares.addAll(candidates.subList(toDial.size(), candidates.size()));

        lastProgress = scheduler.progress();
        long period = Math.max(1, settings.stallTimeout().toMillis());
        stallCheck = watchdog.scheduleWithFixedDelay(
                this::onStallCheck, period, period, TimeUnit.MILLISECONDS);

        for (InetSocketAddress candidate : toDial) {
            dial(candidate);
        }
        return completion;
    }

    /**
     * Nothing has moved for a whole stall period. Either the peers we hold cannot supply
     * what is left, or they have all gone quiet; both are dead ends, so say which one it
     * was and fail closed rather than stay connected to a swarm that cannot finish.
     */
    private void onStallCheck() {
        List<LeecherHandler> open;
        synchronized (this) {
            if (completion.isDone()) {
                return;
            }
            long seen = scheduler.progress();
            if (seen != lastProgress) {
                lastProgress = seen;
                return;
            }
            if (finishIfDone()) {
                return;
            }
            List<Integer> unreachable = unreachableWantedChunks();
            String why = unreachable.isEmpty()
                    ? sessions.size() + " connected peers stopped supplying blocks"
                    : "no connected peer holds chunks " + unreachable;
            completion.completeExceptionally(new IOException("swarm stalled for "
                    + settings.stallTimeout() + ": " + why + ", "
                    + scheduler.pendingBlocks() + " blocks still missing"));
            open = List.copyOf(sessions.values());
            sessions.clear();
        }
        // Drop the sockets now; the event loop itself belongs to whoever owns us.
        open.forEach(LeecherHandler::stop);
    }

    /** Wanted chunks that not one connected peer has advertised. */
    private List<Integer> unreachableWantedChunks() {
        List<Integer> unreachable = new ArrayList<>();
        for (int chunkIndex : scheduler.wantedChunks()) {
            if (!availability.reachable(chunkIndex)) {
                unreachable.add(chunkIndex);
            }
        }
        return unreachable;
    }

    public CompletableFuture<Result> completion() {
        return completion;
    }

    public synchronized int connectedPeers() {
        return sessions.size();
    }

    private void dial(InetSocketAddress seeder) {
        int sessionId = nextSessionId++;
        sessionsStarted++;
        LeecherClient.Request request = new LeecherClient.Request(
                settings.assetId(), settings.peerId(), settings.token(), inventory, assembler,
                settings.session(), settings.connectTimeout(),
                negotiated -> scheduler.viewFor(sessionId, negotiated),
                new Events(sessionId));

        LeecherHandler handler = client.open(seeder, request);
        sessions.put(sessionId, handler);
        handler.completion().whenComplete((result, failure) -> onSessionEnded(sessionId, seeder, failure));
    }

    /**
     * A block arrived from somewhere else, so this session can stop fetching it (P6-04).
     * The scheduler decides which session loses; it holds no sockets, so this is how the
     * decision reaches one. {@code abandon} is the hash-fail path: those bytes belong to
     * a round that already failed, so an arriving payload is dropped rather than kept.
     */
    private void cancelOn(int sessionId, BlockPlan.Block block, boolean abandon) {
        LeecherHandler session;
        synchronized (this) {
            session = sessions.get(sessionId);
        }
        if (session != null) {
            session.cancelBlock(block, abandon);
        }
    }

    /** A chunk verified. Tell every other peer, then see whether we are finished. */
    private void chunkStored(int sourceSessionId, int chunkIndex) {
        List<LeecherHandler> others;
        synchronized (this) {
            others = new ArrayList<>(sessions.size());
            for (Map.Entry<Integer, LeecherHandler> entry : sessions.entrySet()) {
                if (entry.getKey() != sourceSessionId) {
                    others.add(entry.getValue());
                }
            }
        }
        // Outside the lock: announceHave only hops onto another event loop, but a lock
        // held across sessions is a lock two event loops can wait on.
        for (LeecherHandler other : others) {
            other.announceHave(chunkIndex);
        }
        synchronized (this) {
            finishIfDone();
        }
    }

    private synchronized void onSessionEnded(int sessionId, InetSocketAddress seeder, Throwable failure) {
        sessions.remove(sessionId);
        availability.leave(sessionId);
        if (failure != null) {
            sessionsLost++;
            log.debug("swarm session {} to {} ended: {}", sessionId, seeder, failure.toString());
        }
        if (completion.isDone()) {
            return;
        }
        // The session already handed its blocks back on the way out, so the queue is
        // truthful by the time we look at it.
        if (finishIfDone()) {
            return;
        }
        if (!spares.isEmpty()) {
            dial(spares.pollFirst());
            return;
        }
        if (sessions.isEmpty()) {
            completion.completeExceptionally(new IOException("swarm ran out of peers with "
                    + scheduler.pendingBlocks() + " blocks still missing"));
        }
    }

    private boolean finishIfDone() {
        if (completion.isDone()) {
            return true;
        }
        if (!scheduler.isDone()) {
            return false;
        }
        finish();
        return true;
    }

    private void finish() {
        stopStallCheck();
        if (!inventory.complete()) {
            // The queue says there is nothing left, the store says otherwise. Refuse to
            // call that a success.
            completion.completeExceptionally(new IOException(
                    "swarm queue is empty but " + inventory.missing().size() + " chunks are not verified"));
            return;
        }
        completion.complete(new Result(sessionsStarted, sessionsLost));
    }

    @Override
    public void close() {
        List<LeecherHandler> open;
        synchronized (this) {
            if (!completion.isDone()) {
                completion.completeExceptionally(new IOException("swarm closed before the asset completed"));
            }
            stopStallCheck();
            open = List.copyOf(sessions.values());
            sessions.clear();
        }
        open.forEach(LeecherHandler::stop);
        client.close();
        watchdog.shutdownNow();
    }

    /** Never cancels with an interrupt: the stall check may be the caller. */
    private void stopStallCheck() {
        if (stallCheck != null) {
            stallCheck.cancel(false);
            stallCheck = null;
        }
    }

    /** One session's view of the swarm: it can only speak for itself. */
    private final class Events implements SessionEvents {

        private final int sessionId;

        private Events(int sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void remoteInventory(byte[] bitfield) {
            availability.join(sessionId, bitfield);
        }

        @Override
        public void remoteGained(int chunkIndex) {
            availability.note(sessionId, chunkIndex);
        }

        @Override
        public void chunkStored(int chunkIndex) {
            SwarmDownloader.this.chunkStored(sessionId, chunkIndex);
        }
    }

    /**
     * @param maxPeers     how many sessions run at once; the blueprint's B1 figure is 8
     * @param tieBreakSeed fixes the order among equally rare chunks so a run can be replayed
     * @param stallTimeout     how long the whole swarm may make no progress at all before
     *                         it is called dead; must outlast a block timeout, or one slow
     *                         block would look like a stall
     * @param endgameThreshold how few blocks must remain before one may be asked of a
     *                         second peer; {@link SwarmScheduler#NO_ENDGAME} turns it off,
     *                         which is baselines B1 and B2
     */
    public record Settings(
            AssetId assetId,
            PeerId peerId,
            byte[] token,
            LeecherHandler.Settings session,
            Duration connectTimeout,
            int maxPeers,
            long tieBreakSeed,
            Duration stallTimeout,
            int endgameThreshold) {

        /** Phase 5 behaviour: one source per block, all the way to the last one. */
        public static Settings withoutEndgame(AssetId assetId, PeerId peerId, byte[] token,
                                              LeecherHandler.Settings session, Duration connectTimeout,
                                              int maxPeers, long tieBreakSeed, Duration stallTimeout) {
            return new Settings(assetId, peerId, token, session, connectTimeout, maxPeers,
                    tieBreakSeed, stallTimeout, SwarmScheduler.NO_ENDGAME);
        }

        public Settings {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(peerId, "peerId");
            token = Objects.requireNonNull(token, "token").clone();
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(stallTimeout, "stallTimeout");
            if (maxPeers <= 0) {
                throw new IllegalArgumentException("maxPeers must be positive");
            }
            if (stallTimeout.compareTo(session.blockTimeout()) <= 0) {
                throw new IllegalArgumentException("stallTimeout " + stallTimeout
                        + " must be longer than the block timeout " + session.blockTimeout()
                        + ", otherwise a single slow block is read as a dead swarm");
            }
            if (endgameThreshold < 0) {
                throw new IllegalArgumentException("endgameThreshold cannot be negative");
            }
        }

        @Override
        public byte[] token() {
            return token.clone();
        }
    }

    /**
     * @param peersDialled sessions opened, including replacements for peers that dropped
     * @param peersLost    sessions that ended with a failure: the churn the swarm absorbed
     */
    public record Result(int peersDialled, int peersLost) {
    }
}
