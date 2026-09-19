package com.prabin.swarmedge.manifest;

import com.prabin.swarmedge.common.id.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheEvictorTest {

    @TempDir
    Path tempDir;

    @Test
    void quotaEvictsTheLeastRecentlyUsedUnreferencedChunk() throws Exception {
        byte[] first = {1, 1, 1, 1};
        byte[] second = {2, 2, 2, 2};
        byte[] third = {3, 3, 3, 3};
        try (ChunkIndex index = ChunkIndex.open(tempDir.resolve("cache.db"), stepping())) {
            ChunkStore store = new ChunkStore(tempDir, index);
            String oldest = put(store, first);
            String middle = put(store, second);
            String newest = put(store, third);
            CacheEvictor evictor = new CacheEvictor(store, index,
                    new CacheEvictor.Settings(6, 0), () -> Long.MAX_VALUE);

            CacheEvictor.Result result = evictor.evictIfNeeded();

            assertThat(result.chunksRemoved()).isEqualTo(2);
            assertThat(result.bytesRemoved()).isEqualTo(8);
            assertThat(result.stillOver()).isFalse();
            assertThat(store.contains(oldest)).isFalse();
            assertThat(store.contains(middle)).isFalse();
            assertThat(store.contains(newest)).isTrue();
        }
    }

    @Test
    void aReferencedChunkIsNotEvictedEvenWhenTheCacheIsOverQuota() throws Exception {
        byte[] pinnedBytes = {1, 1, 1, 1};
        byte[] spare = {2, 2, 2, 2};
        try (ChunkIndex index = ChunkIndex.open(tempDir.resolve("cache.db"))) {
            ChunkStore store = new ChunkStore(tempDir, index);
            String pinned = put(store, pinnedBytes);
            String other = put(store, spare);
            index.retain(pinned);
            CacheEvictor evictor = new CacheEvictor(store, index,
                    new CacheEvictor.Settings(4, 0), () -> Long.MAX_VALUE);

            CacheEvictor.Result result = evictor.evictIfNeeded();

            assertThat(store.contains(pinned)).isTrue();
            assertThat(store.contains(other)).isFalse();
            assertThat(result.stillOver()).isFalse();
            assertThat(index.find(pinned).orElseThrow().refCount()).isEqualTo(1);
        }
    }

    @Test
    void aDownloadDoesNotEvictItsOwnEarlierChunks() throws Exception {
        byte[] first = {1, 1, 1, 1};
        byte[] second = {2, 2, 2, 2};
        byte[] third = {3, 3, 3, 3};
        try (ChunkIndex index = ChunkIndex.open(tempDir.resolve("cache.db"), stepping())) {
            ChunkStore store = new ChunkStore(tempDir, index);
            CacheEvictor evictor = new CacheEvictor(store, index,
                    new CacheEvictor.Settings(6, 0), () -> Long.MAX_VALUE);
            store.attachEvictor(evictor);

            String a = put(store, first);
            String b = put(store, second);
            String c = put(store, third);

            assertThat(store.contains(a)).isTrue();
            assertThat(store.contains(b)).isTrue();
            assertThat(store.contains(c)).isTrue();
            assertThat(index.verifiedBytes()).isEqualTo(12);
        }
    }

    @Test
    void aLaterSessionMayEvictChunksFromAnEarlierDownload() throws Exception {
        byte[] first = {1, 1, 1, 1};
        byte[] second = {2, 2, 2, 2};
        byte[] third = {3, 3, 3, 3};
        byte[] fourth = {4, 4, 4, 4};
        try (ChunkIndex index = ChunkIndex.open(tempDir.resolve("cache.db"), stepping())) {
            ChunkStore store = new ChunkStore(tempDir, index);
            String oldest = put(store, first);
            String middle = put(store, second);
            String previous = put(store, third);
            CacheEvictor evictor = new CacheEvictor(store, index,
                    new CacheEvictor.Settings(8, 0), () -> Long.MAX_VALUE);
            store.attachEvictor(evictor);

            String newest = put(store, fourth);

            assertThat(store.contains(oldest)).isFalse();
            assertThat(store.contains(middle)).isFalse();
            assertThat(store.contains(previous)).isTrue();
            assertThat(store.contains(newest)).isTrue();
            assertThat(index.verifiedBytes()).isEqualTo(8);
        }
    }

    @Test
    void aJustCommittedChunkIsNotDeletedToMakeRoomForItself() throws Exception {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
        try (ChunkIndex index = ChunkIndex.open(tempDir.resolve("cache.db"))) {
            ChunkStore store = new ChunkStore(tempDir, index);
            String hash = sha256Hex(data);
            CacheEvictor evictor = new CacheEvictor(store, index,
                    new CacheEvictor.Settings(4, 0), () -> Long.MAX_VALUE);
            store.attachEvictor(evictor);

            store.putVerified(hash, data);

            assertThat(store.contains(hash)).isTrue();
            assertThat(index.verifiedBytes()).isEqualTo(data.length);
        }
    }

    @Test
    void minFreeSpaceEvictsUntilNoUnreferencedChunksRemain() throws Exception {
        AtomicLong free = new AtomicLong(0);
        try (ChunkIndex index = ChunkIndex.open(tempDir.resolve("cache.db"))) {
            ChunkStore store = new ChunkStore(tempDir, index);
            String first = put(store, new byte[] {1});
            String pinned = put(store, new byte[] {2});
            index.retain(pinned);
            CacheEvictor evictor = new CacheEvictor(store, index,
                    new CacheEvictor.Settings(Long.MAX_VALUE, 100), free::get);

            CacheEvictor.Result result = evictor.evictIfNeeded();

            assertThat(store.contains(first)).isFalse();
            assertThat(store.contains(pinned)).isTrue();
            assertThat(result.stillOver()).isTrue();
        }
    }

    @Test
    void unlimitedSettingsNeverDeleteAnything() throws Exception {
        try (ChunkIndex index = ChunkIndex.open(tempDir.resolve("cache.db"))) {
            ChunkStore store = new ChunkStore(tempDir, index);
            String hash = put(store, new byte[] {1, 2, 3});
            CacheEvictor evictor = new CacheEvictor(store, index,
                    CacheEvictor.Settings.unlimited(), () -> 0L);

            assertThat(evictor.evictIfNeeded()).isEqualTo(CacheEvictor.Result.NONE);
            assertThat(store.contains(hash)).isTrue();
        }
    }

    @Test
    void aNonPositiveQuotaIsRejected() {
        assertThatThrownBy(() -> new CacheEvictor.Settings(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBytes");
        assertThatThrownBy(() -> new CacheEvictor.Settings(1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minFreeBytes");
    }

    private static String put(ChunkStore store, byte[] data) throws Exception {
        String hash = sha256Hex(data);
        store.putVerified(hash, data);
        return hash;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return Hex.toLowerHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static Clock stepping() {
        Instant t0 = Instant.parse("2026-09-18T00:00:00Z");
        return new Clock() {
            private long seconds = t0.getEpochSecond();

            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochSecond(seconds++);
            }
        };
    }
}
