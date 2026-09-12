package com.prabin.swarmedge.manifest;

import com.prabin.swarmedge.common.id.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void storesVerifiedBytesUnderHashPath() throws Exception {
        byte[] data = {1, 2, 3, 4};
        String hash = sha256Hex(data);
        ChunkStore store = new ChunkStore(tempDir);

        Path saved = store.putVerified(hash, data);

        assertThat(saved).isEqualTo(tempDir.resolve("chunks")
                .resolve(hash.substring(0, 2))
                .resolve(hash.substring(2, 4))
                .resolve(hash + ".chunk"));
        assertThat(Files.readAllBytes(saved)).containsExactly(data);
        assertThat(store.contains(hash)).isTrue();
        assertThat(store.read(hash).orElseThrow()).containsExactly(data);
    }

    @Test
    void rejectsMismatchedHashAndWritesNothing() throws Exception {
        byte[] data = {1, 2, 3, 4};
        String realHash = sha256Hex(data);
        byte[] tampered = {1, 2, 3, 5};
        ChunkStore store = new ChunkStore(tempDir);

        assertThatThrownBy(() -> store.putVerified(realHash, tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunk hash mismatch");
        assertThat(store.contains(realHash)).isFalse();
        assertThat(store.read(realHash)).isEmpty();
    }

    @Test
    void putTwiceIsIdempotent() throws Exception {
        byte[] data = {9};
        String hash = sha256Hex(data);
        ChunkStore store = new ChunkStore(tempDir);

        Path first = store.putVerified(hash, data);
        Path second = store.putVerified(hash, data);

        assertThat(second).isEqualTo(first);
        assertThat(Files.readAllBytes(first)).containsExactly(data);
    }

    @Test
    void rejectsCorruptExistingChunkOnPut() throws Exception {
        byte[] data = {1, 2, 3, 4};
        String hash = sha256Hex(data);
        ChunkStore store = new ChunkStore(tempDir);
        Path saved = store.putVerified(hash, data);
        Files.write(saved, new byte[] {9, 9, 9, 9});

        assertThatThrownBy(() -> store.putVerified(hash, data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corrupt");
        assertThatThrownBy(() -> store.read(hash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corrupt");
    }

    @Test
    void indexesOnlyChunksThatWereHashedAndCommitted() throws Exception {
        byte[] data = {1, 2, 3, 4};
        String hash = sha256Hex(data);
        byte[] tampered = {1, 2, 3, 5};
        try (ChunkIndex index = ChunkIndex.open(tempDir.resolve("cache.db"))) {
            ChunkStore store = new ChunkStore(tempDir, index);

            assertThatThrownBy(() -> store.putVerified(hash, tampered))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(index.find(hash)).isEmpty();

            Path saved = store.putVerified(hash, data);

            ChunkIndex.Entry entry = index.find(hash).orElseThrow();
            assertThat(entry.verified()).isTrue();
            assertThat(entry.length()).isEqualTo(data.length);
            assertThat(entry.storedAt()).isEqualTo(saved.toAbsolutePath().normalize().toString());
        }
    }

    @Test
    void aCorruptStoredChunkStopsCountingAsCached() throws Exception {
        byte[] data = {1, 2, 3, 4};
        String hash = sha256Hex(data);
        try (ChunkIndex index = ChunkIndex.open(tempDir.resolve("cache.db"))) {
            ChunkStore store = new ChunkStore(tempDir, index);
            Path saved = store.putVerified(hash, data);
            Files.write(saved, new byte[] {9, 9, 9, 9});

            assertThatThrownBy(() -> store.read(hash)).isInstanceOf(IllegalArgumentException.class);

            assertThat(index.find(hash).orElseThrow().verified()).isFalse();
            assertThat(index.verifiedHashes()).isEmpty();
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return Hex.toLowerHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
