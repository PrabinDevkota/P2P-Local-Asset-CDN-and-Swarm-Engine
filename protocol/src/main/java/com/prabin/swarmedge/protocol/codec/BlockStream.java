package com.prabin.swarmedge.protocol.codec;

/**
 * What a receiver sees while a BLOCK is arriving (blueprint §7.3).
 *
 * <p>A BLOCK is the only message whose payload is bulk data, so it is the only one
 * that is not handed over as a finished {@link com.prabin.swarmedge.protocol.PeerFrame}.
 * The decoder announces the metadata, forwards the bytes as they land, and then says
 * the block is finished. The receiver therefore never has to hold the whole transfer
 * in heap to find out what it was.
 *
 * <p>{@link Data} carries plain arrays rather than {@code ByteBuf} slices on purpose:
 * the bytes cross from the Netty event loop to a disk thread, and copies do not need
 * reference-count bookkeeping to survive that hop.
 */
public sealed interface BlockStream {

    long requestId();

    /** Metadata parsed, payload not yet read. */
    record Begin(long requestId, int chunkIndex, int blockOffset, int blockLength) implements BlockStream {
    }

    /**
     * A slice of payload that has arrived. {@code offsetInBlock} is relative to
     * {@link Begin#blockOffset()}, so the receiver can place bytes without counting.
     * The array is handed over, not shared: the decoder keeps no reference to it.
     */
    record Data(long requestId, int offsetInBlock, byte[] bytes) implements BlockStream {
    }

    /** All {@code blockLength} bytes have been forwarded. */
    record End(long requestId, int chunkIndex, int blockOffset, int blockLength) implements BlockStream {
    }
}
