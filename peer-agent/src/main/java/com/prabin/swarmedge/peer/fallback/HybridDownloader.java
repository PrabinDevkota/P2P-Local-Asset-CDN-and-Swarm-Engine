package com.prabin.swarmedge.peer.fallback;

import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.peer.laps.PeerSelector;
import com.prabin.swarmedge.peer.origin.OriginChunkFetcher;
import com.prabin.swarmedge.peer.swarm.SwarmDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Progressive fallback over a live swarm (blueprint P8-03, §12.1).
 *
 * <p>t=0 is verified cache plus the peers already handed to {@link #start}. A same-site
 * EDGE is offered after {@link FallbackPolicy#delay} for that rung, and only when the
 * peer pipeline is still below the fill target. Origin Range GETs start on the later
 * rung, capped by {@code maxOriginInFlight}. First verified copy of a chunk wins:
 * a swarm hit cancels that origin GET; an origin hit drops the chunk from the peer
 * queue so the sessions CANCEL it.
 *
 * <p>This class owns the clock, not the sockets. The caller still closes the
 * {@link SwarmDownloader}. Origin is chunk-granular HTTP; it is not a protocol v1 peer.
 */
public final class HybridDownloader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HybridDownloader.class);
    private static final long RECONCILE_PERIOD_MILLIS = 20L;

    private final SwarmDownloader swarm;
    private final OriginChunkFetcher origin;
    private final ChunkInventory inventory;
    private final FallbackPolicy policy;
    private final List<PeerSelector.Candidate> edges;
    private final long clientSeed;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final CompletableFuture<Result> completion = new CompletableFuture<>();
    private final Set<Integer> originFetching = ConcurrentHashMap.newKeySet();
    private final Set<Integer> originFailed = ConcurrentHashMap.newKeySet();
    private final AtomicInteger originChunks = new AtomicInteger();
    private final AtomicBoolean edgeOffered = new AtomicBoolean();
    private final AtomicBoolean originOpen = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();

    public HybridDownloader(SwarmDownloader swarm, OriginChunkFetcher origin, ChunkInventory inventory,
                            FallbackPolicy policy, long clientSeed, List<PeerSelector.Candidate> edges) {
        this(swarm, origin, inventory, policy, clientSeed, edges, null);
    }

    public HybridDownloader(SwarmDownloader swarm, OriginChunkFetcher origin, ChunkInventory inventory,
                            FallbackPolicy policy, long clientSeed, List<PeerSelector.Candidate> edges,
                            ScheduledExecutorService scheduler) {
        this.swarm = Objects.requireNonNull(swarm, "swarm");
        this.origin = origin;
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clientSeed = clientSeed;
        this.edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        if (scheduler != null) {
            this.scheduler = scheduler;
            this.ownsScheduler = false;
        } else {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "hybrid-fallback");
                thread.setDaemon(true);
                return thread;
            });
            this.ownsScheduler = true;
        }
    }

    /**
     * Dial {@code peers} immediately. EDGE and origin are admitted later by the policy,
     * not by waiting a fixed five seconds and then giving up on the swarm.
     */
    public CompletableFuture<Result> start(List<PeerSelector.Candidate> peers) {
        Objects.requireNonNull(peers, "peers");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("this hybrid download has already been started");
        }

        List<PeerSelector.Candidate> initial = new ArrayList<>(peers);
        if (initial.isEmpty() && !edges.isEmpty()) {
            initial.addAll(edges);
            edgeOffered.set(true);
        }
        if (initial.isEmpty()) {
            completion.completeExceptionally(new IOException("no peers, EDGE, or origin path to start from"));
            return completion;
        }

        swarm.startPreferring(initial).whenComplete(this::onSwarmDone);
        scheduler.schedule(this::offerEdge, policy.delay(FallbackPolicy.Rung.EDGE, clientSeed).toMillis(),
                TimeUnit.MILLISECONDS);
        if (origin != null) {
            scheduler.schedule(this::openOrigin, policy.delay(FallbackPolicy.Rung.ORIGIN, clientSeed).toMillis(),
                    TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(this::reconcile, RECONCILE_PERIOD_MILLIS, RECONCILE_PERIOD_MILLIS,
                    TimeUnit.MILLISECONDS);
        }
        return completion;
    }

    public boolean edgeOffered() {
        return edgeOffered.get();
    }

    public int originChunks() {
        return originChunks.get();
    }

    public long originBytes() {
        return origin == null ? 0L : origin.bytesReceived();
    }

    public int originCancelled() {
        return origin == null ? 0 : origin.cancelled();
    }

    private void offerEdge() {
        try {
            if (completion.isDone() || edges.isEmpty() || edgeOffered.get()) {
                return;
            }
            if (swarm.pipelineFill() >= policy.pipelineFillTarget()) {
                log.debug("EDGE delayed: pipeline fill {} is at or above {}", swarm.pipelineFill(),
                        policy.pipelineFillTarget());
                return;
            }
            edgeOffered.set(true);
            swarm.offer(edges);
        } catch (RuntimeException e) {
            log.warn("EDGE offer failed: {}", e.toString());
        }
    }

    private void openOrigin() {
        try {
            if (completion.isDone() || origin == null || inventory.complete()) {
                return;
            }
            originOpen.set(true);
            swarm.holdStall(true);
            fillOrigin();
        } catch (RuntimeException e) {
            log.warn("origin open failed: {}", e.toString());
        }
    }

    private void fillOrigin() {
        if (!originOpen.get() || origin == null || completion.isDone() || inventory.complete()) {
            releaseStallIfQuiet();
            return;
        }
        for (int chunkIndex : inventory.missing()) {
            if (originFetching.size() >= policy.maxOriginInFlight()) {
                break;
            }
            if (originFailed.contains(chunkIndex) || !originFetching.add(chunkIndex)) {
                continue;
            }
            ChunkEntry chunk = inventory.chunk(chunkIndex);
            origin.fetch(chunk).whenComplete((ok, error) -> onOrigin(chunkIndex, ok, error));
        }
        releaseStallIfQuiet();
    }

    private void onOrigin(int chunkIndex, Boolean stored, Throwable error) {
        originFetching.remove(chunkIndex);
        if (Boolean.TRUE.equals(stored)) {
            if (!inventory.has(chunkIndex)) {
                // Count before settle: acceptForeignChunk can finish the swarm here.
                originChunks.incrementAndGet();
                inventory.markStored(chunkIndex);
            }
            swarm.acceptForeignChunk(chunkIndex);
        } else if (error != null) {
            originFailed.add(chunkIndex);
            log.debug("origin chunk {} failed: {}", chunkIndex, error.toString());
        }
        fillOrigin();
    }

    /**
     * Swarm verified a chunk the origin is still paying for: abort that GET so the
     * flash crowd does not keep the bytes.
     */
    private void reconcile() {
        try {
            if (origin == null || completion.isDone()) {
                return;
            }
            for (int chunkIndex : Set.copyOf(originFetching)) {
                if (inventory.has(chunkIndex)) {
                    origin.cancel(chunkIndex);
                }
            }
            if (originOpen.get()) {
                fillOrigin();
            } else {
                releaseStallIfQuiet();
            }
        } catch (RuntimeException e) {
            log.warn("origin reconcile failed: {}", e.toString());
        }
    }

    private void releaseStallIfQuiet() {
        if (!originOpen.get() || inventory.complete() || originFetching.isEmpty()) {
            swarm.holdStall(false);
        }
    }

    private void onSwarmDone(SwarmDownloader.Result result, Throwable error) {
        originOpen.set(false);
        if (origin != null) {
            origin.cancelAll();
        }
        if (error != null) {
            completion.completeExceptionally(error);
            return;
        }
        completion.complete(new Result(result, originChunks.get(), origin == null ? 0L : origin.bytesReceived(),
                origin == null ? 0 : origin.cancelled(), edgeOffered.get()));
    }

    @Override
    public void close() {
        if (!completion.isDone()) {
            completion.completeExceptionally(new IOException("hybrid download closed before the asset completed"));
        }
        if (origin != null) {
            origin.cancelAll();
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    /**
     * @param originChunks    chunks origin verified first
     * @param originBytes     Range body bytes received before cancel or commit
     * @param originCancelled in-flight GETs aborted because a peer already had the chunk
     * @param edgeOffered     whether the EDGE rung was admitted
     */
    public record Result(
            SwarmDownloader.Result swarm,
            int originChunks,
            long originBytes,
            int originCancelled,
            boolean edgeOffered
    ) {
        public Result {
            Objects.requireNonNull(swarm, "swarm");
        }
    }
}
