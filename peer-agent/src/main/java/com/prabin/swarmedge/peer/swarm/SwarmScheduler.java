package com.prabin.swarmedge.peer.swarm;

import com.prabin.swarmedge.peer.chunk.BlockPlan;
import com.prabin.swarmedge.peer.chunk.BlockSource;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.manifest.ChunkEntry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * One queue of wanted blocks, handed out to many sessions (blueprint P5-02, P5-03, §8.3).
 *
 * <p>Three rules from §8.3 live here. A block is never handed to two peers at once in
 * normal mode, so a leased block is nobody else's to ask for. A block that comes back
 * from a timeout or a dropped peer returns to the queue without touching chunks that
 * have already verified. And chunk choice is rarest-first across everyone we are
 * connected to, not first-come order, so the scarce bytes spread before the common ones.
 *
 * <p>The exception is the endgame (P6-04). Near the end there are fewer blocks left than
 * peers to ask, so idle peers pile up behind whichever straggler holds the last block —
 * one slow peer sets the finish time no matter how fast everyone else was. Once the
 * queue is down to {@code endgameThreshold} blocks, a block may go to a second peer as
 * well, and the first copy to arrive cancels the other. It costs one duplicate block of
 * bandwidth to stop the tail being decided by the worst peer in the swarm.
 *
 * <p>Two sources, never three. The cost is bounded on purpose: duplicating to everybody
 * would turn the tail of every transfer into a broadcast, which is the failure mode that
 * makes naive endgame handling worse than none.
 *
 * <p>Each session works through {@link #viewFor(int, int)}, which is the per-session
 * {@link BlockSource}. The view exists so a session can only give back what it holds:
 * releasing another session's lease would let one misbehaving peer stall the swarm.
 *
 * <p>Shared across event loops, so every method is synchronized. The work inside the
 * lock is queue arithmetic and one sort of the wanted chunks.
 */
public final class SwarmScheduler {

    /** §8.3 caps endgame duplication at two sources for one block. */
    public static final int MAX_SOURCES_PER_BLOCK = 2;

    /** No duplication at all, which is the Phase 5 behaviour and baselines B1 and B2. */
    public static final int NO_ENDGAME = 0;

    private final ChunkInventory inventory;
    private final ChunkAvailability availability;
    private final int blockSize;
    private final int endgameThreshold;
    private final DuplicateCanceller canceller;
    private final int maxOutstanding;
    private final SourcePreference preference;

    /** Blocks nobody is fetching yet, by chunk. A chunk with no entry is not wanted. */
    private final Map<Integer, Deque<BlockPlan.Block>> unleased = new LinkedHashMap<>();

    /** Who is fetching each block. More than one holder only happens in the endgame. */
    private final Map<BlockPlan.Block, Set<Integer>> leases = new LinkedHashMap<>();

    /** Blocks whose bytes have landed. Not worth duplicating, and not worth cancelling. */
    private final Set<BlockPlan.Block> delivered = new HashSet<>();

    private long progress;

    /** No endgame: every block goes to exactly one peer, as in Phase 5. */
    public SwarmScheduler(ChunkInventory inventory, ChunkAvailability availability, int blockSize) {
        this(inventory, availability, blockSize, NO_ENDGAME, DuplicateCanceller.NONE);
    }

    /**
     * @param endgameThreshold how few blocks must remain before a block may go to a
     *                         second peer; {@link #NO_ENDGAME} disables it
     * @param canceller        how the loser of a duplicated block is called off, which
     *                         the scheduler cannot do itself because it holds no sockets
     */
    public SwarmScheduler(ChunkInventory inventory, ChunkAvailability availability, int blockSize,
                          int endgameThreshold, DuplicateCanceller canceller) {
        this(inventory, availability, blockSize, endgameThreshold, canceller,
                Integer.MAX_VALUE, SourcePreference.NONE);
    }

    /**
     * @param maxOutstanding per-session pipeline depth, used when preferring a better
     *                       source: a worse peer is skipped only while a better one still
     *                       has room in its window
     * @param preference     how to score a session for decision B; {@link SourcePreference#NONE}
     *                       keeps first-come (baseline B1)
     */
    public SwarmScheduler(ChunkInventory inventory, ChunkAvailability availability, int blockSize,
                          int endgameThreshold, DuplicateCanceller canceller, int maxOutstanding,
                          SourcePreference preference) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.availability = Objects.requireNonNull(availability, "availability");
        this.canceller = Objects.requireNonNull(canceller, "canceller");
        this.preference = Objects.requireNonNull(preference, "preference");
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        if (endgameThreshold < 0) {
            throw new IllegalArgumentException("endgameThreshold cannot be negative");
        }
        if (maxOutstanding <= 0) {
            throw new IllegalArgumentException("maxOutstanding must be positive");
        }
        if (availability.chunkCount() != inventory.chunkCount()) {
            throw new IllegalArgumentException("availability covers " + availability.chunkCount()
                    + " chunks but the manifest has " + inventory.chunkCount());
        }
        this.blockSize = blockSize;
        this.endgameThreshold = endgameThreshold;
        this.maxOutstanding = maxOutstanding;
        for (int chunkIndex : inventory.missing()) {
            unleased.put(chunkIndex, blocksOf(chunkIndex));
        }
    }

    /**
     * How the scheduler reaches back into a session to cancel a duplicate it no longer
     * needs. The scheduler owns queue state, not connections, so the owner supplies this.
     */
    @FunctionalInterface
    public interface DuplicateCanceller {

        DuplicateCanceller NONE = (sessionId, block) -> { };

        void cancel(int sessionId, BlockPlan.Block block);

        /**
         * The whole chunk is being rebuilt. Drop this request even if payload is already
         * arriving: those bytes belong to the round that just failed the hash.
         */
        default void abandon(int sessionId, BlockPlan.Block block) {
            cancel(sessionId, block);
        }
    }

    /**
     * Decision B: which session should take the next block of a chunk.
     *
     * <p>{@link #NONE} is first-come, which is baseline B1. A real preference scores
     * higher for a better source (nearer, faster, healthier) so a worse session is
     * skipped while a better one still has room in its pipeline.
     */
    @FunctionalInterface
    public interface SourcePreference {

        SourcePreference NONE = sessionId -> Double.NaN;

        /** Higher is better. {@link Double#NaN} means this session is not scored. */
        double score(int sessionId);
    }

    public int blockSize() {
        return blockSize;
    }

    /**
     * A session's handle on the queue.
     *
     * @param negotiatedBlockSize the session's agreed ceiling, which must be able to
     *                            carry a full block or the session cannot take part
     */
    public BlockSource viewFor(int sessionId, int negotiatedBlockSize) {
        if (negotiatedBlockSize < blockSize) {
            throw new IllegalArgumentException("peer caps blocks at " + negotiatedBlockSize
                    + " but this swarm cuts chunks into " + blockSize + " byte blocks");
        }
        return new View(sessionId);
    }

    public synchronized int pendingBlocks() {
        int waiting = 0;
        for (Deque<BlockPlan.Block> blocks : unleased.values()) {
            waiting += blocks.size();
        }
        return waiting + leases.size();
    }

    public synchronized int leasedBlocks() {
        return leases.size();
    }

    /** How many peers are fetching one block: 1 normally, 2 at most in the endgame. */
    public synchronized int sourcesFor(BlockPlan.Block block) {
        Set<Integer> holders = leases.get(Objects.requireNonNull(block, "block"));
        return holders == null ? 0 : holders.size();
    }

    /** Blocks currently going to more than one peer, which is the endgame's whole cost. */
    public synchronized int duplicatedBlocks() {
        int duplicated = 0;
        for (Set<Integer> holders : leases.values()) {
            if (holders.size() > 1) {
                duplicated++;
            }
        }
        return duplicated;
    }

    /** True once the queue is short enough for a block to be worth insuring (§8.3). */
    public synchronized boolean inEndgame() {
        return endgameThreshold != NO_ENDGAME && !isDone() && pendingBlocks() <= endgameThreshold;
    }

    /** True when no block is waiting and none is being fetched: the asset is done. */
    public synchronized boolean isDone() {
        return unleased.isEmpty() && leases.isEmpty();
    }

    /** Chunks still wanted, for diagnostics and for tests that assert on progress. */
    public synchronized List<Integer> wantedChunks() {
        return List.copyOf(unleased.keySet());
    }

    /**
     * A counter that rises whenever the swarm actually moves: a block was handed to a
     * peer, or a chunk verified. It says nothing about how much moved, only that
     * something did, which is what an owner needs to tell a slow swarm from a wedged one.
     *
     * <p>Two situations look identical from the queue alone — every peer is busy, and no
     * peer holds anything we want — and only this counter separates them.
     */
    public synchronized long progress() {
        return progress;
    }

    private synchronized Optional<BlockPlan.Block> lease(int sessionId, IntPredicate remoteHasChunk) {
        Objects.requireNonNull(remoteHasChunk, "remoteHasChunk");
        // Scarcest first, but only chunks this peer holds and that still have work left.
        for (int chunkIndex : availability.rarestFirst(
                index -> unleased.containsKey(index) && remoteHasChunk.test(index))) {
            if (shouldDefer(sessionId, chunkIndex)) {
                continue;
            }
            Deque<BlockPlan.Block> blocks = unleased.get(chunkIndex);
            BlockPlan.Block block = blocks.pollFirst();
            if (block == null) {
                // Every block of this chunk is already being fetched elsewhere.
                continue;
            }
            if (blocks.isEmpty()) {
                unleased.remove(chunkIndex);
            }
            hold(sessionId, block);
            return Optional.of(block);
        }
        // Nothing unclaimed. Normally this peer just waits; in the endgame it is worth
        // asking it for something another peer is already slow at.
        return duplicateForEndgame(sessionId, remoteHasChunk);
    }

    /**
     * True when a better-scoring connected peer already has this chunk in its pipeline
     * and still has room for more. Skipping then keeps the better source busy. An idle
     * better peer (zero leases) is not assumed to be about to ask — that would leave this
     * session waiting on a handshake that has not happened yet.
     */
    private boolean shouldDefer(int sessionId, int chunkIndex) {
        double mine = preference.score(sessionId);
        if (Double.isNaN(mine)) {
            return false;
        }
        for (int other : availability.holdersOf(chunkIndex)) {
            if (other == sessionId) {
                continue;
            }
            double theirs = preference.score(other);
            if (Double.isNaN(theirs) || theirs <= mine) {
                continue;
            }
            int outstanding = outstandingOf(other);
            if (outstanding > 0 && outstanding < maxOutstanding) {
                return true;
            }
        }
        return false;
    }

    private int outstandingOf(int sessionId) {
        int held = 0;
        for (Set<Integer> holders : leases.values()) {
            if (holders.contains(sessionId)) {
                held++;
            }
        }
        return held;
    }

    /**
     * Find a block worth asking a second peer for.
     *
     * <p>Only near the end, only blocks with exactly one source so far, and never one
     * whose bytes have already landed. Rarest-first order still applies: if two
     * stragglers are outstanding, the scarcer chunk is the one worth insuring.
     */
    private Optional<BlockPlan.Block> duplicateForEndgame(int sessionId, IntPredicate remoteHasChunk) {
        if (endgameThreshold == NO_ENDGAME || pendingBlocks() > endgameThreshold) {
            return Optional.empty();
        }
        for (int chunkIndex : availability.rarestFirst(remoteHasChunk::test)) {
            for (BlockPlan.Block block : leases.keySet()) {
                if (block.chunkIndex() != chunkIndex || delivered.contains(block)) {
                    continue;
                }
                Set<Integer> holders = leases.get(block);
                if (holders.size() >= MAX_SOURCES_PER_BLOCK || holders.contains(sessionId)) {
                    continue;
                }
                hold(sessionId, block);
                return Optional.of(block);
            }
        }
        return Optional.empty();
    }

    private void hold(int sessionId, BlockPlan.Block block) {
        leases.computeIfAbsent(block, key -> new LinkedHashSet<>()).add(sessionId);
        progress++;
    }

    /**
     * These bytes landed. Any other peer still fetching the same block is called off,
     * which is the second half of P6-04: first arrival wins and the loser is cancelled
     * rather than waited on.
     *
     * <p>The winner keeps its lease. A block is only truly finished when its chunk
     * verifies, and until then a session that dies must still hand its work back.
     */
    private void complete(int sessionId, BlockPlan.Block block) {
        Objects.requireNonNull(block, "block");
        List<Integer> losers = new ArrayList<>();
        synchronized (this) {
            Set<Integer> holders = leases.get(block);
            if (holders == null || !holders.contains(sessionId)) {
                // Already settled, restarted, or never ours: nothing to call off.
                return;
            }
            delivered.add(block);
            for (Integer holder : holders) {
                if (holder != sessionId) {
                    losers.add(holder);
                }
            }
            holders.removeAll(losers);
        }
        // Outside the lock: cancelling hops onto another session's event loop, and a
        // lock held across sessions is a lock two event loops can wait on.
        for (int loser : losers) {
            canceller.cancel(loser, block);
        }
    }

    private synchronized void release(int sessionId, BlockPlan.Block block) {
        Objects.requireNonNull(block, "block");
        Set<Integer> holders = leases.get(block);
        if (holders == null || !holders.remove(sessionId)) {
            // Not ours to give back. Either it was already returned, or its chunk has
            // since verified and the block no longer exists.
            return;
        }
        if (holders.isEmpty()) {
            requeueUnheld(block);
        }
        // Otherwise the other endgame source is still on it, so the block is not idle.
    }

    private synchronized void surrender(int sessionId) {
        for (BlockPlan.Block block : List.copyOf(leases.keySet())) {
            Set<Integer> holders = leases.get(block);
            if (holders != null && holders.remove(sessionId) && holders.isEmpty()) {
                requeueUnheld(block);
            }
        }
    }

    private void requeueUnheld(BlockPlan.Block block) {
        leases.remove(block);
        delivered.remove(block);
        unleased.computeIfAbsent(block.chunkIndex(), index -> new ArrayDeque<>()).addFirst(block);
    }

    /**
     * Rebuild a chunk from nothing. Its assembled bytes failed the manifest hash, so
     * every block has to be fetched again even if some of them were fine.
     *
     * <p>Anyone still fetching this chunk is called off: their in-flight bytes belong to
     * the round that just failed, and leaving those leases in place would let the same
     * block be handed out again while the old request is still open.
     */
    private void restartChunk(int chunkIndex) {
        List<Map.Entry<Integer, BlockPlan.Block>> toCancel = new ArrayList<>();
        synchronized (this) {
            if (inventory.has(chunkIndex)) {
                // Another session already produced a verified copy; nothing to rebuild.
                return;
            }
            for (Map.Entry<BlockPlan.Block, Set<Integer>> entry : List.copyOf(leases.entrySet())) {
                if (entry.getKey().chunkIndex() != chunkIndex) {
                    continue;
                }
                for (int sessionId : entry.getValue()) {
                    toCancel.add(Map.entry(sessionId, entry.getKey()));
                }
            }
            forgetChunk(chunkIndex);
            unleased.put(chunkIndex, blocksOf(chunkIndex));
        }
        for (Map.Entry<Integer, BlockPlan.Block> work : toCancel) {
            canceller.abandon(work.getKey(), work.getValue());
        }
    }

    /** A chunk verified into the store, so it leaves the queue for good. */
    private synchronized void settle(int chunkIndex) {
        unleased.remove(chunkIndex);
        forgetChunk(chunkIndex);
        progress++;
    }

    private void forgetChunk(int chunkIndex) {
        leases.keySet().removeIf(block -> block.chunkIndex() == chunkIndex);
        delivered.removeIf(block -> block.chunkIndex() == chunkIndex);
    }

    private Deque<BlockPlan.Block> blocksOf(int chunkIndex) {
        ChunkEntry chunk = inventory.chunk(chunkIndex);
        if (chunk.length() > Integer.MAX_VALUE) {
            // blockOffset is a u32 field, so a chunk this large could not be addressed.
            throw new IllegalArgumentException("chunk " + chunkIndex + " is too large to request in blocks");
        }
        Deque<BlockPlan.Block> blocks = new ArrayDeque<>();
        for (long offset = 0; offset < chunk.length(); offset += blockSize) {
            int length = (int) Math.min(blockSize, chunk.length() - offset);
            blocks.addLast(new BlockPlan.Block(chunkIndex, (int) offset, length));
        }
        return blocks;
    }

    /** What one session sees: the shared queue, but it can only return its own leases. */
    private final class View implements BlockSource {

        private final int sessionId;

        private View(int sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public int blockSize() {
            return blockSize;
        }

        @Override
        public Optional<BlockPlan.Block> next(IntPredicate remoteHasChunk) {
            return lease(sessionId, remoteHasChunk);
        }

        @Override
        public void requeue(BlockPlan.Block block) {
            release(sessionId, block);
        }

        @Override
        public void completed(BlockPlan.Block block) {
            complete(sessionId, block);
        }

        @Override
        public void requeueChunk(int chunkIndex) {
            restartChunk(chunkIndex);
        }

        @Override
        public void dropChunk(int chunkIndex) {
            settle(chunkIndex);
        }

        @Override
        public void surrender() {
            SwarmScheduler.this.surrender(sessionId);
        }

        @Override
        public boolean isEmpty() {
            return isDone();
        }

        @Override
        public int pendingBlocks() {
            return SwarmScheduler.this.pendingBlocks();
        }

        /** Never: in a swarm, running dry means someone else is working on the rest. */
        @Override
        public boolean soleSource() {
            return false;
        }
    }
}
