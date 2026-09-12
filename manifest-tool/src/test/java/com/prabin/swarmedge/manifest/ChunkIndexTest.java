package com.prabin.swarmedge.manifest;

import com.prabin.swarmedge.common.id.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkIndexTest {

    @TempDir
    Path tempDir;

    private static final Instant T0 = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    void recordsLengthRefCountAndStoredPath() throws Exception {
        String hash = sha256Hex(new byte[] {1, 2, 3});
        try (ChunkIndex index = openAt(T0)) {
            Path stored = tempDir.resolve("chunks").resolve(hash + ".chunk");

            index.recordVerified(hash, 3, stored);

            ChunkIndex.Entry entry = index.find(hash).orElseThrow();
            assertThat(entry.chunkHash()).isEqualTo(hash);
            assertThat(entry.length()).isEqualTo(3);
            assertThat(entry.verified()).isTrue();
            assertThat(entry.refCount()).isZero();
            assertThat(entry.lastAccess()).isEqualTo(T0);
            assertThat(entry.storedAt()).isEqualTo(stored.toAbsolutePath().normalize().toString());
        }
    }

    @Test
    void reopeningKeepsRowsSoWarmStartNeedsNoRehash() throws Exception {
        String hash = sha256Hex(new byte[] {7});
        try (ChunkIndex index = openAt(T0)) {
            index.recordVerified(hash, 1, tempDir.resolve("a.chunk"));
        }

        try (ChunkIndex reopened = openAt(T0)) {
            assertThat(reopened.verifiedHashes()).containsExactly(hash);
            assertThat(reopened.verifiedBytes()).isEqualTo(1);
        }
    }

    @Test
    void recordAgainKeepsRefCountAndMovesLastAccessForward() throws Exception {
        String hash = sha256Hex(new byte[] {4, 4});
        try (ChunkIndex index = openAt(T0)) {
            index.recordVerified(hash, 2, tempDir.resolve("a.chunk"));
            index.retain(hash);
            index.retain(hash);

            index.recordVerified(hash, 2, tempDir.resolve("a.chunk"));

            ChunkIndex.Entry entry = index.find(hash).orElseThrow();
            assertThat(entry.refCount()).isEqualTo(2);

            index.release(hash);
            index.release(hash);
            index.release(hash);
            assertThat(index.find(hash).orElseThrow().refCount()).isZero();
        }
    }

    @Test
    void unverifiedRowIsNotCachedAndNotAnEvictionCandidate() throws Exception {
        String hash = sha256Hex(new byte[] {5});
        try (ChunkIndex index = openAt(T0)) {
            index.recordVerified(hash, 1, tempDir.resolve("a.chunk"));

            index.markUnverified(hash);

            assertThat(index.find(hash).orElseThrow().verified()).isFalse();
            assertThat(index.verifiedHashes()).isEmpty();
            assertThat(index.verifiedBytes()).isZero();
            assertThat(index.evictionCandidates(10)).isEmpty();
        }
    }

    @Test
    void evictionSkipsReferencedChunksAndPrefersLeastRecentlyUsed() throws Exception {
        String oldest = sha256Hex(new byte[] {1});
        String newer = sha256Hex(new byte[] {2});
        String pinned = sha256Hex(new byte[] {3});
        try (ChunkIndex index = ChunkIndex.open(tempDir.resolve("cache.db"), stepping())) {
            index.recordVerified(oldest, 1, tempDir.resolve("1.chunk"));
            index.recordVerified(newer, 1, tempDir.resolve("2.chunk"));
            index.recordVerified(pinned, 1, tempDir.resolve("3.chunk"));
            index.retain(pinned);

            assertThat(index.evictionCandidates(10))
                    .extracting(ChunkIndex.Entry::chunkHash)
                    .containsExactly(oldest, newer);

            index.touch(oldest);
            assertThat(index.evictionCandidates(1))
                    .extracting(ChunkIndex.Entry::chunkHash)
                    .containsExactly(newer);
        }
    }

    @Test
    void removeDropsTheRowButIsNotAFileDelete() throws Exception {
        String hash = sha256Hex(new byte[] {8});
        try (ChunkIndex index = openAt(T0)) {
            index.recordVerified(hash, 1, tempDir.resolve("a.chunk"));

            index.remove(hash);

            assertThat(index.find(hash)).isEmpty();
        }
    }

    @Test
    void rejectsAHashThatIsNotSixtyFourHexCharacters() throws Exception {
        try (ChunkIndex index = openAt(T0)) {
            assertThatThrownBy(() -> index.find("abcd"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("64 hex");
            assertThatThrownBy(() -> index.recordVerified("zz".repeat(32), 1, tempDir))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private ChunkIndex openAt(Instant instant) throws Exception {
        return ChunkIndex.open(tempDir.resolve("cache.db"), Clock.fixed(instant, ZoneOffset.UTC));
    }

    /** Each call is one second later, so last-access ordering is deterministic. */
    private static Clock stepping() {
        return new Clock() {
            private long seconds = T0.getEpochSecond();

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

    private static String sha256Hex(byte[] data) throws Exception {
        return Hex.toLowerHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
