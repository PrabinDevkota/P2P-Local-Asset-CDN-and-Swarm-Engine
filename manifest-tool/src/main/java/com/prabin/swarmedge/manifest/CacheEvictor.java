package com.prabin.swarmedge.manifest;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * LRU / quota / min-free-space eviction over a {@link ChunkStore} (blueprint P7-02, §9.1).
 *
 * <p>Candidates come from the index: verified, {@code refCount = 0}, least recently
 * used first. Referenced chunks are not in that list, and {@link ChunkStore#remove}
 * refuses them anyway, so a pinned or retained release cannot be deleted by a quota
 * miss. A chunk that was just committed is protected for the same pass — otherwise a
 * single object larger than the quota would be stored and immediately thrown away.
 *
 * <p>If everything left is pinned and the cache is still over quota, eviction stops
 * rather than deleting those rows. The cache is then over budget on purpose: the
 * alternative is to break a release that still needs those bytes.
 *
 * <p>Usable disk space is injected so a test can say "the volume is full" without
 * filling a real disk. Production passes {@code FileStore::getUsableSpace}.
 */
public final class CacheEvictor {

    private final ChunkStore store;
    private final ChunkIndex index;
    private final Settings settings;
    private final UsableSpace usableSpace;

    public CacheEvictor(ChunkStore store, ChunkIndex index, Settings settings, UsableSpace usableSpace) {
        this.store = Objects.requireNonNull(store, "store");
        this.index = Objects.requireNonNull(index, "index");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.usableSpace = Objects.requireNonNull(usableSpace, "usableSpace");
    }

    /**
     * Delete unreferenced chunks until the cache fits, or until none are left to
     * delete. {@code protectHash} is the chunk committed in this pass; it is skipped.
     */
    public Result evictIfNeeded(String protectHash) throws IOException {
        if (settings.isUnlimited()) {
            return Result.NONE;
        }
        int removed = 0;
        long bytesRemoved = 0L;
        while (needsEviction()) {
            ChunkIndex.Entry victim = nextVictim(protectHash);
            if (victim == null) {
                return new Result(removed, bytesRemoved, true);
            }
            store.remove(victim.chunkHash());
            removed++;
            bytesRemoved += victim.length();
        }
        return new Result(removed, bytesRemoved, false);
    }

    public Result evictIfNeeded() throws IOException {
        return evictIfNeeded(null);
    }

    private boolean needsEviction() throws IOException {
        if (index.verifiedBytes() > settings.maxBytes()) {
            return true;
        }
        return settings.minFreeBytes() > 0 && usableSpace.bytes() < settings.minFreeBytes();
    }

    private ChunkIndex.Entry nextVictim(String protectHash) throws IOException {
        String protect = protectHash == null ? "" : protectHash;
        List<ChunkIndex.Entry> batch = index.evictionCandidates(16);
        for (ChunkIndex.Entry entry : batch) {
            if (!entry.chunkHash().equals(protect)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * @param maxBytes     hard ceiling on verified bytes; {@link Long#MAX_VALUE} is unlimited
     * @param minFreeBytes refuse to sit below this much usable space; 0 disables the check
     */
    public record Settings(long maxBytes, long minFreeBytes) {

        public Settings {
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive");
            }
            if (minFreeBytes < 0) {
                throw new IllegalArgumentException("minFreeBytes cannot be negative");
            }
        }

        public static Settings unlimited() {
            return new Settings(Long.MAX_VALUE, 0L);
        }

        public boolean isUnlimited() {
            return maxBytes == Long.MAX_VALUE && minFreeBytes == 0L;
        }
    }

    /**
     * @param chunksRemoved how many files this pass deleted
     * @param bytesRemoved  their recorded lengths, which is what the quota reads
     * @param stillOver     true when pinned or protected chunks keep the cache over budget
     */
    public record Result(int chunksRemoved, long bytesRemoved, boolean stillOver) {

        static final Result NONE = new Result(0, 0L, false);
    }

    @FunctionalInterface
    public interface UsableSpace {

        long bytes() throws IOException;
    }
}
