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
 *
 * <p>Which chunks we hold is read from the store <em>once</em>, when this object is
 * built, and then kept in memory. Asking the filesystem on every call looks harmless
 * until you count the calls: a handshake advertises the whole inventory and a seeder
 * checks one chunk per REQUEST, so a large asset would put thousands of blocking
 * {@code stat} calls on a Netty event loop (blueprint §21.2). Construction and
 * {@link #rescan()} do that I/O; nothing else here touches the disk.
 *
 * <p>Build this off the event loop: the constructor and {@link #rescan()} are the only
 * methods that touch the disk. Everything else is in-memory and synchronized, because a
 * swarm shares one inventory between its scheduler and every session, and two sessions
 * can verify different chunks at the same moment.
 */
public final class ChunkInventory {

    private final List<ChunkEntry> chunks;
    private final ChunkStore store;
    private final byte[] present;

    public ChunkInventory(ReleaseManifest manifest, ChunkStore store) {
        Objects.requireNonNull(manifest, "manifest");
        this.chunks = List.copyOf(manifest.chunks());
        this.store = Objects.requireNonNull(store, "store");
        this.present = ChunkBitfield.empty(chunks.size());
        rescan();
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** True when the verified bytes of this chunk are already on disk. */
    public synchronized boolean has(int chunkIndex) {
        requireKnownChunk(chunkIndex);
        return ChunkBitfield.get(present, chunkIndex);
    }

    /**
     * Record a chunk that has just been verified into the store. Cheaper and more
     * truthful than a rescan: the caller has just proved this one chunk is there.
     */
    public synchronized void markStored(int chunkIndex) {
        requireKnownChunk(chunkIndex);
        ChunkBitfield.set(present, chunkIndex);
    }

    /** Re-read the store. Blocking I/O, so never call this from an event loop. */
    public void rescan() {
        for (int i = 0; i < chunks.size(); i++) {
            boolean stored = store.contains(chunks.get(i).sha256());
            if (stored) {
                markStored(i);
            }
        }
    }

    public ChunkEntry chunk(int chunkIndex) {
        requireKnownChunk(chunkIndex);
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
    public synchronized byte[] bitfield() {
        return present.clone();
    }

    public synchronized List<Integer> missing() {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (!ChunkBitfield.get(present, i)) {
                missing.add(i);
            }
        }
        return List.copyOf(missing);
    }

    public synchronized boolean complete() {
        return missing().isEmpty();
    }

    private void requireKnownChunk(int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= chunks.size()) {
            throw new ProtocolViolationException(
                    "chunkIndex out of range: " + chunkIndex + " of " + chunks.size());
        }
    }
}
