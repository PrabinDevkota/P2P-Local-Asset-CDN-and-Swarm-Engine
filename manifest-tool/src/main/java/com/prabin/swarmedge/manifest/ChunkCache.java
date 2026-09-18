package com.prabin.swarmedge.manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A {@link ChunkStore} plus its SQLite index and the eviction policy that acts on
 * both (blueprint Phase 7).
 *
 * <p>Runners and agents open this rather than a bare store, so a restart sees
 * {@code cache.db}, a cache hit bumps LRU, and a commit that would grow past quota
 * evicts something unreferenced first. Tests that do not care about metadata still
 * construct {@link ChunkStore} alone.
 */
public final class ChunkCache implements AutoCloseable {

    public static final String INDEX_FILE = "cache.db";

    private final ChunkStore store;
    private final ChunkIndex index;
    private final CacheEvictor evictor;

    private ChunkCache(ChunkStore store, ChunkIndex index, CacheEvictor evictor) {
        this.store = store;
        this.index = index;
        this.evictor = evictor;
    }

    public static ChunkCache open(Path root) throws IOException {
        return open(root, CacheEvictor.Settings.unlimited());
    }

    public static ChunkCache open(Path root, CacheEvictor.Settings settings) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(settings, "settings");
        ChunkIndex index = ChunkIndex.open(root.resolve(INDEX_FILE));
        ChunkStore store = new ChunkStore(root, index);
        store.reconcileIndex();
        CacheEvictor.UsableSpace disk = settings.isUnlimited()
                ? () -> Long.MAX_VALUE
                : () -> Files.getFileStore(store.chunksDirectory()).getUsableSpace();
        CacheEvictor evictor = new CacheEvictor(store, index, settings, disk);
        if (!settings.isUnlimited()) {
            store.attachEvictor(evictor);
        }
        return new ChunkCache(store, index, evictor);
    }

    public ChunkStore store() {
        return store;
    }

    public ChunkIndex index() {
        return index;
    }

    public CacheEvictor evictor() {
        return evictor;
    }

    @Override
    public void close() throws IOException {
        index.close();
    }
}
