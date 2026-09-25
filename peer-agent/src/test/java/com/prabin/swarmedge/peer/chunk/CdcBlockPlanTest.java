package com.prabin.swarmedge.peer.chunk;

import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.FastCdcChunker;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CdcBlockPlanTest {

    @TempDir
    Path tempDir;

    @Test
    void variableChunkLengthsStillCoverTheFileInBlocks() throws Exception {
        byte[] body = new byte[1_500];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 11 + 4);
        }
        Path file = Files.write(tempDir.resolve("cdc.bin"), body);
        FastCdcChunker chunker = new FastCdcChunker(32, 64, 256);
        List<ChunkEntry> chunks = chunker.chunk(file);
        ReleaseManifest manifest = ReleaseManifestFactory.unsigned(
                "game-x", "1.4.0", file, chunker.spec(), chunks,
                "2026-09-25T00:00:00Z", "2026-10-25T00:00:00Z", 1, "release-key");
        ChunkInventory inventory = new ChunkInventory(manifest, new ChunkStore(tempDir.resolve("store")));
        BlockPlan plan = new BlockPlan(inventory, 64);

        List<BlockPlan.Block> blocks = new ArrayList<>();
        while (!plan.isEmpty()) {
            blocks.add(plan.next(index -> true).orElseThrow());
        }

        assertThat(blocks.stream().mapToLong(BlockPlan.Block::blockLength).sum()).isEqualTo(body.length);
        assertThat(chunks.stream().mapToLong(ChunkEntry::length).distinct().count()).isGreaterThan(1);
    }
}
