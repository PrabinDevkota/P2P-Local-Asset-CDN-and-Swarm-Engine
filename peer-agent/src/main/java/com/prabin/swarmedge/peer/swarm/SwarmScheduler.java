package com.prabin.swarmedge.peer.swarm;

import com.prabin.swarmedge.peer.chunk.BlockPlan;
import com.prabin.swarmedge.peer.chunk.BlockSource;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.manifest.ChunkEntry;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * <p>Each session works through {@link #viewFor(int, int)}, which is the per-session
 * {@link BlockSource}. The view exists so a session can only give back what it holds:
 * releasing another session's lease would let one misbehaving peer stall the swarm.
 *
 * <p>Shared across event loops, so every method is synchronized. The work inside the
 * lock is queue arithmetic and one sort of the wanted chunks.
 */
public final class SwarmScheduler {

    private final ChunkInventory inventory;
    private final ChunkAvailability availability;
    private final int blockSize;

    /** Blocks nobody is fetching yet, by chunk. A chunk with no entry is not wanted. */
    private final Map<Integer, Deque<BlockPlan.Block>> unleased = new LinkedHashMap<>();
    private final Map<BlockPlan.Block, Integer> leases = new HashMap<>();

    private long progress;

    public SwarmScheduler(ChunkInventory inventory, ChunkAvailability availability, int blockSize) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.availability = Objects.requireNonNull(availability, "availability");
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        if (availability.chunkCount() != inventory.chunkCount()) {
            throw new IllegalArgumentException("availability covers " + availability.chunkCount()
                    + " chunks but the manifest has " + inventory.chunkCount());
        }
        this.blockSize = blockSize;
        for (int chunkIndex : inventory.missing()) {
            unleased.put(chunkIndex, blocksOf(chunkIndex));
        }
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
            Deque<BlockPlan.Block> blocks = unleased.get(chunkIndex);
            BlockPlan.Block block = blocks.pollFirst();
            if (block == null) {
                // Every block of this chunk is already being fetched elsewhere.
                continue;
            }
            if (blocks.isEmpty()) {
                unleased.remove(chunkIndex);
            }
            leases.put(block, sessionId);
            progress++;
            return Optional.of(block);
        }
        return Optional.empty();
    }

    private synchronized void release(int sessionId, BlockPlan.Block block) {
        Objects.requireNonNull(block, "block");
        Integer holder = leases.get(block);
        if (holder == null || holder != sessionId) {
            // Not ours to give back. Either it was already returned, or its chunk has
            // since verified and the block no longer exists.
            return;
        }
        leases.remove(block);
        unleased.computeIfAbsent(block.chunkIndex(), index -> new ArrayDeque<>()).addFirst(block);
    }

    private synchronized void surrender(int sessionId) {
        for (BlockPlan.Block block : List.copyOf(leases.keySet())) {
            if (leases.get(block) == sessionId) {
                leases.remove(block);
                unleased.computeIfAbsent(block.chunkIndex(), index -> new ArrayDeque<>()).addFirst(block);
            }
        }
    }

    /**
     * Rebuild a chunk from nothing. Its assembled bytes failed the manifest hash, so
     * every block has to be fetched again even if some of them were fine.
     */
    private synchronized void restartChunk(int chunkIndex) {
        if (inventory.has(chunkIndex)) {
            // Another session already produced a verified copy; nothing to rebuild.
            return;
        }
        leases.keySet().removeIf(block -> block.chunkIndex() == chunkIndex);
        unleased.put(chunkIndex, blocksOf(chunkIndex));
    }

    /** A chunk verified into the store, so it leaves the queue for good. */
    private synchronized void settle(int chunkIndex) {
        unleased.remove(chunkIndex);
        leases.keySet().removeIf(block -> block.chunkIndex() == chunkIndex);
        progress++;
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
