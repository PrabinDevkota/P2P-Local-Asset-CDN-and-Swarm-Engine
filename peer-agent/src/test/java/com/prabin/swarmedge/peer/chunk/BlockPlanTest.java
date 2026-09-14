package com.prabin.swarmedge.peer.chunk;

import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockPlanTest {

    private static final int CHUNK_SIZE = 256;
    private static final int BLOCK_SIZE = 64;
    private static final int TAIL_BYTES = 50;
    private static final int FULL_CHUNKS = 2;
    private static final IntPredicate REMOTE_HAS_EVERYTHING = index -> true;

    @TempDir
    Path tempDir;

    private byte[] original;
    private ReleaseManifest manifest;
    private ChunkStore store;
    private ChunkInventory inventory;

    @BeforeEach
    void publishAsset() throws Exception {
        original = deterministicBytes(FULL_CHUNKS * CHUNK_SIZE + TAIL_BYTES);
        Path published = Files.write(tempDir.resolve("game-x-1.4.0.bin"), original);
        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        manifest = ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                "2026-09-13T00:00:00Z", "2026-10-13T00:00:00Z", 1, "release-key-2026-01");
        store = new ChunkStore(tempDir.resolve("data"));
        inventory = new ChunkInventory(manifest, store);
    }

    @Test
    void coversEveryMissingByteExactlyOnce() {
        BlockPlan plan = new BlockPlan(inventory, BLOCK_SIZE);

        List<BlockPlan.Block> blocks = drain(plan);

        assertThat(blocks).hasSize(4 + 4 + 1);
        long total = blocks.stream().mapToLong(BlockPlan.Block::blockLength).sum();
        assertThat(total).isEqualTo(original.length);
        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    void theShortFinalChunkGetsAShortFinalBlock() {
        BlockPlan plan = new BlockPlan(inventory, BLOCK_SIZE);

        List<BlockPlan.Block> blocks = drain(plan);

        assertThat(blocks.getLast())
                .isEqualTo(new BlockPlan.Block(FULL_CHUNKS, 0, TAIL_BYTES));
    }

    @Test
    void chunksAlreadyInTheCacheAreNeverRequested() throws Exception {
        cache(0);
        // The inventory reads the store when it is built, which is what a peer does at
        // startup. Caching behind its back is not something the plan can see.
        BlockPlan plan = new BlockPlan(new ChunkInventory(manifest, store), BLOCK_SIZE);

        assertThat(drain(plan)).extracting(BlockPlan.Block::chunkIndex).doesNotContain(0);
        assertThat(plan.pendingBlocks()).isZero();
    }

    @Test
    void blocksAreOnlyOfferedForChunksTheFarSideHolds() {
        BlockPlan plan = new BlockPlan(inventory, BLOCK_SIZE);
        IntPredicate onlyChunkOne = index -> index == 1;

        List<BlockPlan.Block> offered = new ArrayList<>();
        plan.next(onlyChunkOne).ifPresent(offered::add);
        plan.next(onlyChunkOne).ifPresent(offered::add);

        assertThat(offered).extracting(BlockPlan.Block::chunkIndex).containsOnly(1);
        // The rest is still owed, just not by this peer.
        assertThat(plan.pendingBlocks()).isEqualTo(7);
    }

    @Test
    void aPeerWithNothingIsOfferedNothingRatherThanBeingAskedAnyway() {
        BlockPlan plan = new BlockPlan(inventory, BLOCK_SIZE);

        assertThat(plan.next(index -> false)).isEmpty();
        assertThat(plan.pendingBlocks()).isEqualTo(9);
    }

    @Test
    void aTimedOutBlockGoesBackToTheFrontOfTheQueue() {
        BlockPlan plan = new BlockPlan(inventory, BLOCK_SIZE);
        BlockPlan.Block first = plan.next(REMOTE_HAS_EVERYTHING).orElseThrow();
        BlockPlan.Block second = plan.next(REMOTE_HAS_EVERYTHING).orElseThrow();

        plan.requeue(first);

        assertThat(plan.next(REMOTE_HAS_EVERYTHING)).contains(first);
        assertThat(plan.next(REMOTE_HAS_EVERYTHING)).isNotEqualTo(second);
    }

    @Test
    void aFailedChunkIsRebuiltInOrderAtTheFrontOfTheQueue() {
        BlockPlan plan = new BlockPlan(inventory, BLOCK_SIZE);
        // Spend all of chunk 0 and one block of chunk 1.
        for (int i = 0; i < 5; i++) {
            plan.next(REMOTE_HAS_EVERYTHING).orElseThrow();
        }

        plan.requeueChunk(0);

        assertThat(plan.pendingBlocks()).isEqualTo(4 + 3 + 1);
        List<BlockPlan.Block> next = List.of(
                plan.next(REMOTE_HAS_EVERYTHING).orElseThrow(),
                plan.next(REMOTE_HAS_EVERYTHING).orElseThrow(),
                plan.next(REMOTE_HAS_EVERYTHING).orElseThrow(),
                plan.next(REMOTE_HAS_EVERYTHING).orElseThrow());
        assertThat(next).containsExactly(
                new BlockPlan.Block(0, 0, BLOCK_SIZE),
                new BlockPlan.Block(0, BLOCK_SIZE, BLOCK_SIZE),
                new BlockPlan.Block(0, 2 * BLOCK_SIZE, BLOCK_SIZE),
                new BlockPlan.Block(0, 3 * BLOCK_SIZE, BLOCK_SIZE));
    }

    @Test
    void requeueingAChunkDoesNotDuplicateBlocksStillInTheQueue() {
        BlockPlan plan = new BlockPlan(inventory, BLOCK_SIZE);

        plan.requeueChunk(1);

        assertThat(plan.pendingBlocks()).isEqualTo(9);
        assertThat(plan.next(REMOTE_HAS_EVERYTHING)).contains(new BlockPlan.Block(1, 0, BLOCK_SIZE));
    }

    @Test
    void aChunkThatArrivedElsewhereIsDroppedFromThePlan() {
        BlockPlan plan = new BlockPlan(inventory, BLOCK_SIZE);

        plan.dropChunk(0);

        assertThat(plan.pendingBlocks()).isEqualTo(5);
        assertThat(drain(plan)).extracting(BlockPlan.Block::chunkIndex).doesNotContain(0);
    }

    @Test
    void aBlockSizeBiggerThanTheChunkAsksForTheWholeChunkAtOnce() {
        BlockPlan plan = new BlockPlan(inventory, 4 * CHUNK_SIZE);

        List<BlockPlan.Block> blocks = drain(plan);

        assertThat(blocks).containsExactly(
                new BlockPlan.Block(0, 0, CHUNK_SIZE),
                new BlockPlan.Block(1, 0, CHUNK_SIZE),
                new BlockPlan.Block(2, 0, TAIL_BYTES));
    }

    @Test
    void refusesANonsensicalBlockSize() {
        assertThatThrownBy(() -> new BlockPlan(inventory, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BlockPlan(inventory, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private List<BlockPlan.Block> drain(BlockPlan plan) {
        List<BlockPlan.Block> blocks = new ArrayList<>();
        plan.next(REMOTE_HAS_EVERYTHING).ifPresent(blocks::add);
        while (!plan.isEmpty()) {
            blocks.add(plan.next(REMOTE_HAS_EVERYTHING).orElseThrow());
        }
        return blocks;
    }

    private void cache(int chunkIndex) throws Exception {
        ChunkEntry chunk = manifest.chunks().get(chunkIndex);
        byte[] data = Arrays.copyOfRange(original, (int) chunk.offset(), (int) (chunk.offset() + chunk.length()));
        store.putVerified(chunk.sha256(), data);
    }

    private static byte[] deterministicBytes(int length) {
        byte[] out = new byte[length];
        new Random(20260913).nextBytes(out);
        return out;
    }
}
