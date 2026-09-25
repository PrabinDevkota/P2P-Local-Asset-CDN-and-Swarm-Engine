package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FastCdcChunkerTest {

    @TempDir
    Path tempDir;

    @Test
    void cutsCoverTheFileAndRepeat() throws Exception {
        byte[] body = new byte[8_000];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31 + 7);
        }
        Path file = tempDir.resolve("asset.bin");
        Files.write(file, body);
        FastCdcChunker chunker = new FastCdcChunker(64, 256, 1024);

        var first = chunker.chunk(file);
        var second = chunker.chunk(file);

        assertThat(second).isEqualTo(first);
        long covered = first.stream().mapToLong(ChunkEntry::length).sum();
        assertThat(covered).isEqualTo(body.length);
        assertThat(first).allSatisfy(chunk -> {
            assertThat(chunk.length()).isBetween(1L, 1024L);
        });
        assertThat(first.stream().limit(first.size() - 1L))
                .allSatisfy(chunk -> assertThat(chunk.length()).isGreaterThanOrEqualTo(64));
    }

    @Test
    void anInsertLeavesTheChunkBeforeTheEdit() throws Exception {
        byte[] original = new byte[4_000];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i * 13 + 3);
        }
        Path before = tempDir.resolve("v1.bin");
        Files.write(before, original);
        FastCdcChunker chunker = new FastCdcChunker(32, 128, 512);
        var v1 = chunker.chunk(before);
        assertThat(v1.size()).isGreaterThan(1);
        int insertAt = (int) v1.get(0).length();
        byte[] edited = VersionMutator.insert(original, insertAt, new byte[] {1, 2, 3, 4});
        Path after = tempDir.resolve("v2.bin");
        Files.write(after, edited);

        var v2 = chunker.chunk(after);

        assertThat(v2.get(0).sha256()).isEqualTo(v1.get(0).sha256());
        assertThat(v2.get(0).length()).isEqualTo(v1.get(0).length());
    }

    @Test
    void aFixedManifestAndACdcManifestBothExposeChunkEntries() throws Exception {
        byte[] body = new byte[500];
        Arrays.fill(body, (byte) 3);
        Path file = tempDir.resolve("both.bin");
        Files.write(file, body);
        Chunker fixed = new FileChunker(128);
        Chunker cdc = new FastCdcChunker(32, 64, 128);

        assertThat(fixed.chunk(file)).isNotEmpty();
        assertThat(cdc.chunk(file)).isNotEmpty();
        assertThat(fixed.spec().mode()).isEqualTo("FIXED");
        assertThat(cdc.spec().fastCdc()).isTrue();
        long cdcBytes = cdc.chunk(file).stream().mapToLong(ChunkEntry::length).sum();
        assertThat(cdcBytes).isEqualTo(body.length);
    }
}
