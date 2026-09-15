package com.prabin.swarmedge.peer.chunk;

import com.prabin.swarmedge.manifest.ChunkEntry;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * The queue of blocks this peer still wants, in the order it will ask for them.
 *
 * <p>Phase 4 asks in chunk order from a single peer. The interesting part is not the
 * order but the bookkeeping around failure: a block can come back to the front when a
 * request times out, and a whole chunk can come back when its hash does not match, so
 * the plan has to be able to un-spend work it already handed out.
 *
 * <p>Blocks are only offered for chunks the far side claims to hold, which is why
 * {@link #next(IntPredicate)} takes the remote inventory rather than assuming it.
 *
 * <p>This is the single-peer {@link BlockSource}: the queue belongs to one session, so
 * running out of blocks means the transfer cannot finish.
 */
public final class BlockPlan implements BlockSource {

    private final ChunkInventory inventory;
    private final int blockSize;
    private final Deque<Block> pending = new ArrayDeque<>();

    public BlockPlan(ChunkInventory inventory, int blockSize) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        this.blockSize = blockSize;
        for (int chunkIndex : inventory.missing()) {
            appendBlocksOf(chunkIndex, pending::addLast);
        }
    }

    @Override
    public int blockSize() {
        return blockSize;
    }

    @Override
    public boolean soleSource() {
        return true;
    }

    /** Nothing to hand back: a single-peer plan dies with its session. */
    @Override
    public void surrender() {
    }

    /** The next block worth asking for, skipping chunks the far side does not have. */
    @Override
    public Optional<Block> next(IntPredicate remoteHasChunk) {
        Objects.requireNonNull(remoteHasChunk, "remoteHasChunk");
        Iterator<Block> candidates = pending.iterator();
        while (candidates.hasNext()) {
            Block candidate = candidates.next();
            if (remoteHasChunk.test(candidate.chunkIndex())) {
                candidates.remove();
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Put one block back at the front, for example after a request timed out. */
    @Override
    public void requeue(Block block) {
        Objects.requireNonNull(block, "block");
        pending.addFirst(block);
    }

    /**
     * Start a chunk over. Used when assembled bytes fail the manifest hash: partial
     * progress is gone, so every block of that chunk has to be fetched again.
     */
    @Override
    public void requeueChunk(int chunkIndex) {
        dropChunk(chunkIndex);
        Deque<Block> restored = new ArrayDeque<>();
        appendBlocksOf(chunkIndex, restored::addLast);
        while (!restored.isEmpty()) {
            pending.addFirst(restored.removeLast());
        }
    }

    /**
     * Nothing to do: with one peer a block only ever had one source, so there is no
     * duplicate to call off.
     */
    @Override
    public void completed(Block block) {
    }

    /** Forget a chunk, for example because it arrived from somewhere else. */
    @Override
    public void dropChunk(int chunkIndex) {
        pending.removeIf(block -> block.chunkIndex() == chunkIndex);
    }

    @Override
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    @Override
    public int pendingBlocks() {
        return pending.size();
    }

    private void appendBlocksOf(int chunkIndex, Consumer<Block> sink) {
        ChunkEntry chunk = inventory.chunk(chunkIndex);
        if (chunk.length() > Integer.MAX_VALUE) {
            // blockOffset is a u32 field, so a chunk this large could not be addressed.
            throw new IllegalArgumentException("chunk " + chunkIndex + " is too large to request in blocks");
        }
        for (long offset = 0; offset < chunk.length(); offset += blockSize) {
            int length = (int) Math.min(blockSize, chunk.length() - offset);
            sink.accept(new Block(chunkIndex, (int) offset, length));
        }
    }

    public record Block(int chunkIndex, int blockOffset, int blockLength) {
    }
}
