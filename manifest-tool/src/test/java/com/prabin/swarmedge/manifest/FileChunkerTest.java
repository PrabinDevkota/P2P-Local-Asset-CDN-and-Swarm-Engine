package com.prabin.swarmedge.manifest;

import com.prabin.swarmedge.common.id.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileChunkerTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyFileHasNoChunks() throws Exception {
        Path file = tempDir.resolve("empty.bin");
        Files.write(file, new byte[0]);

        assertThat(new FileChunker(4).chunk(file)).isEmpty();
    }

    @Test
    void singleByteIsOneShortChunk() throws Exception {
        Path file = tempDir.resolve("one.bin");
        Files.write(file, new byte[] {0x2a});

        List<ChunkEntry> chunks = new FileChunker(4).chunk(file);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().index()).isZero();
        assertThat(chunks.getFirst().offset()).isZero();
        assertThat(chunks.getFirst().length()).isEqualTo(1);
        assertThat(chunks.getFirst().sha256()).isEqualTo(sha256Hex(new byte[] {0x2a}));
    }

    @Test
    void exactMultipleAndRemainderChunks() throws Exception {
        byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Path file = tempDir.resolve("ten.bin");
        Files.write(file, data);

        List<ChunkEntry> chunks = new FileChunker(4).chunk(file);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).isEqualTo(new ChunkEntry(0, 0, 4, sha256Hex(data, 0, 4)));
        assertThat(chunks.get(1)).isEqualTo(new ChunkEntry(1, 4, 4, sha256Hex(data, 4, 4)));
        assertThat(chunks.get(2)).isEqualTo(new ChunkEntry(2, 8, 2, sha256Hex(data, 8, 2)));
    }

    @Test
    void rejectsChunkSizeTooLargeForByteArray() {
        assertThatThrownBy(() -> new FileChunker(Integer.MAX_VALUE + 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte array");
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return sha256Hex(data, 0, data.length);
    }

    private static String sha256Hex(byte[] data, int offset, int length) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(data, offset, length);
        return Hex.toLowerHex(digest.digest());
    }
}
