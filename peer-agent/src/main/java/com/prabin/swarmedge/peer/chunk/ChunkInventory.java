package com.prabin.swarmedge.peer.chunk;

import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import com.prabin.swarmedge.protocol.msg.ChunkBitfield;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What this peer can serve for one asset, and the bounds any REQUEST must respect.
 *
 * <p>The manifest decides what exists and the store decides what we hold, so both
 * questions are answered here rather than in a Netty handler. A handler that has to
 * reason about chunk geometry is a handler that will eventually get it wrong.
 */
public final class ChunkInventory {

    private final List<ChunkEntry> chunks;
    private final ChunkStore store;

    public ChunkInventory(ReleaseManifest manifest, ChunkStore store) {
        Objects.requireNonNull(manifest, "manifest");
        this.chunks = List.copyOf(manifest.chunks());
        this.store = Objects.requireNonNull(store, "store");
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** True when the verified bytes of this chunk are already on disk. */
    public boolean has(int chunkIndex) {
        return store.contains(chunk(chunkIndex).sha256());
    }

    public ChunkEntry chunk(int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= chunks.size()) {
            throw new ProtocolViolationException(
                    "chunkIndex out of range: " + chunkIndex + " of " + chunks.size());
        }
        return chunks.get(chunkIndex);
    }

    /**
     * Bounds check for a REQUEST or an inbound BLOCK (blueprint §7.4). The chunk must
     * exist in the manifest and the byte span must sit inside it, so a peer cannot use
     * a large offset to read past the chunk it asked for.
     */
    public ChunkEntry requireInRange(int chunkIndex, long blockOffset, long blockLength) {
        ChunkEntry entry = chunk(chunkIndex);
        if (blockOffset < 0 || blockLength <= 0) {
            throw new ProtocolViolationException(
                    "block span must be positive: offset=" + blockOffset + " length=" + blockLength);
        }
        if (blockOffset + blockLength > entry.length()) {
            throw new ProtocolViolationException("block span leaves chunk " + chunkIndex
                    + ": " + blockOffset + "+" + blockLength + " > " + entry.length());
        }
        return entry;
    }

    /** Inventory to advertise in BITFIELD. */
    public byte[] bitfield() {
        byte[] bits = ChunkBitfield.empty(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            if (store.contains(chunks.get(i).sha256())) {
                ChunkBitfield.set(bits, i);
            }
        }
        return bits;
    }

    public List<Integer> missing() {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (!store.contains(chunks.get(i).sha256())) {
                missing.add(i);
            }
        }
        return List.copyOf(missing);
    }

    public boolean complete() {
        return missing().isEmpty();
    }
}
