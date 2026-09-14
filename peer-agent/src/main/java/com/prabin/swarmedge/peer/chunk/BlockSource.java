package com.prabin.swarmedge.peer.chunk;

import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * Where a session gets its next block, and where it gives one back.
 *
 * <p>With one peer this is just that peer's own queue ({@link BlockPlan}). In a swarm it
 * is a view onto a queue shared by every session, because "never ask two peers for the
 * same block" is not a decision a single connection can make on its own.
 *
 * <p>The difference the session has to care about is {@link #soleSource()}. Running out
 * of work means the transfer has failed when there is nobody else to ask, and means
 * nothing at all when there is.
 *
 * <p>A shared implementation is touched by several event loops and must be thread-safe.
 */
public interface BlockSource {

    /** Byte length of a full block. Requests are sized by this, so both ends must agree. */
    int blockSize();

    /**
     * Claim the next block worth asking this peer for. A claimed block belongs to the
     * caller until it is completed, requeued, or surrendered.
     *
     * @param remoteHasChunk what the far side of this session claims to hold
     */
    Optional<BlockPlan.Block> next(IntPredicate remoteHasChunk);

    /** Give one block back, for example after a request timed out or was refused. */
    void requeue(BlockPlan.Block block);

    /** Start a chunk over because its assembled bytes failed the manifest hash. */
    void requeueChunk(int chunkIndex);

    /** Forget a chunk: it is verified and stored, so nobody needs to fetch it. */
    void dropChunk(int chunkIndex);

    /** Give back everything this session still holds, because the session is over. */
    void surrender();

    /** True when there is nothing left to fetch from anyone. */
    boolean isEmpty();

    /** Blocks still owed, for diagnostics and for the message when a session gives up. */
    int pendingBlocks();

    /**
     * True when this session is the only way to get the remaining blocks, which makes
     * running dry a failure rather than an idle moment.
     */
    boolean soleSource();

    /**
     * Builds the source once the block size is settled.
     *
     * <p>The size is only known after HELLO_ACK, because the far side advertises a
     * ceiling and the smaller of the two wins. A swarm may refuse a peer here: every
     * session in one swarm has to cut chunks into the same blocks for a shared queue to
     * mean anything.
     */
    @FunctionalInterface
    interface Factory {
        BlockSource create(int negotiatedBlockSize);
    }
}
