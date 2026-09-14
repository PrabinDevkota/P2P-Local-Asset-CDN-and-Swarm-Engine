package com.prabin.swarmedge.peer.swarm;

import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.peer.chunk.BlockPlan;
import com.prabin.swarmedge.peer.chunk.BlockSource;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.protocol.msg.ChunkBitfield;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SwarmSchedulerTest {

    private static final int CHUNK_SIZE = 64;
    private static final int BLOCK_SIZE = 16;
    private static final int TAIL_BYTES = 10;
    private static final int FULL_CHUNKS = 5;
    private static final int CHUNKS = FULL_CHUNKS + 1;
    private static final int BLOCKS_PER_CHUNK = CHUNK_SIZE / BLOCK_SIZE;
    private static final long SEED = 20260914L;
    private static final IntPredicate HAS_EVERYTHING = index -> true;

    @TempDir
    Path tempDir;

    private byte[] original;
    private ReleaseManifest manifest;
    private ChunkStore store;
    private ChunkInventory inventory;
    private ChunkAvailability availability;

    @BeforeEach
    void publishAsset() throws Exception {
        original = deterministicBytes(FULL_CHUNKS * CHUNK_SIZE + TAIL_BYTES);
        Path published = Files.write(tempDir.resolve("game-x-1.4.0.bin"), original);
        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        manifest = ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                "2026-09-13T00:00:00Z", "2026-10-13T00:00:00Z", 1, "release-key-2026-01");
        store = new ChunkStore(tempDir.resolve("data"));
        inventory = new ChunkInventory(manifest, store);
        availability = new ChunkAvailability(CHUNKS, SEED);
    }

    @Test
    void coversEveryMissingByteExactlyOnceAcrossAllSessions() {
        SwarmScheduler scheduler = scheduler();
        BlockSource first = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource second = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        List<BlockPlan.Block> taken = new ArrayList<>();
        alternatingDrain(first, second, taken);

        assertThat(taken).hasSize(FULL_CHUNKS * BLOCKS_PER_CHUNK + 1);
        assertThat(new HashSet<>(taken)).hasSameSizeAs(taken);
        assertThat(taken.stream().mapToLong(BlockPlan.Block::blockLength).sum())
                .isEqualTo(original.length);
    }

    @Test
    void aBlockHandedToOnePeerIsNotOfferedToAnother() {
        SwarmScheduler scheduler = scheduler();
        BlockSource first = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource second = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        BlockPlan.Block taken = first.next(HAS_EVERYTHING).orElseThrow();

        assertThat(second.next(HAS_EVERYTHING).orElseThrow()).isNotEqualTo(taken);
        assertThat(scheduler.leasedBlocks()).isEqualTo(2);
    }

    @Test
    void theScarcestChunkIsAskedForFirst() {
        SwarmScheduler scheduler = scheduler();
        BlockSource view = scheduler.viewFor(1, BLOCK_SIZE);
        // Everyone holds chunk 0; only one peer holds chunk 3.
        availability.join(1, bits(0, 3));
        availability.join(2, bits(0));
        availability.join(3, bits(0));

        BlockPlan.Block first = view.next(HAS_EVERYTHING).orElseThrow();

        assertThat(first.chunkIndex()).isEqualTo(3);
    }

    @Test
    void aChunkNobodyHoldsIsNeverHandedOut() {
        SwarmScheduler scheduler = scheduler();
        BlockSource view = scheduler.viewFor(1, BLOCK_SIZE);
        availability.join(1, bits(2));

        List<BlockPlan.Block> taken = drain(view, HAS_EVERYTHING);

        assertThat(taken).isNotEmpty();
        assertThat(taken).allMatch(block -> block.chunkIndex() == 2);
        // The rest of the asset is still owed, just unreachable for now.
        assertThat(scheduler.isDone()).isFalse();
    }

    @Test
    void aPeerIsOnlyOfferedChunksItActuallyHolds() {
        SwarmScheduler scheduler = scheduler();
        BlockSource view = scheduler.viewFor(1, BLOCK_SIZE);
        everyoneHasEverything(1);

        List<BlockPlan.Block> taken = drain(view, index -> index == 4);

        assertThat(taken).isNotEmpty();
        assertThat(taken).allMatch(block -> block.chunkIndex() == 4);
    }

    @Test
    void chunksAlreadyVerifiedAreNeverQueued() throws Exception {
        cache(0);
        cache(3);
        SwarmScheduler scheduler = scheduler();
        everyoneHasEverything(1);

        assertThat(scheduler.wantedChunks()).containsExactlyInAnyOrder(1, 2, 4, 5);
        assertThat(drain(scheduler.viewFor(1, BLOCK_SIZE), HAS_EVERYTHING))
                .extracting(BlockPlan.Block::chunkIndex)
                .doesNotContain(0, 3);
    }

    @Test
    void theShortFinalChunkGetsAShortFinalBlock() {
        SwarmScheduler scheduler = scheduler();
        everyoneHasEverything(1);

        List<BlockPlan.Block> tail = drain(scheduler.viewFor(1, BLOCK_SIZE), index -> index == FULL_CHUNKS);

        assertThat(tail).containsExactly(new BlockPlan.Block(FULL_CHUNKS, 0, TAIL_BYTES));
    }

    @Test
    void aTimedOutBlockGoesBackAndCanBeFetchedByAnotherPeer() {
        SwarmScheduler scheduler = scheduler();
        BlockSource slow = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource other = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);
        BlockPlan.Block block = slow.next(HAS_EVERYTHING).orElseThrow();

        slow.requeue(block);

        assertThat(scheduler.leasedBlocks()).isZero();
        assertThat(other.next(HAS_EVERYTHING)).contains(block);
    }

    @Test
    void onePeerCannotGiveBackAnotherPeersWork() {
        SwarmScheduler scheduler = scheduler();
        BlockSource mine = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource theirs = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);
        BlockPlan.Block held = mine.next(HAS_EVERYTHING).orElseThrow();

        theirs.requeue(held);

        // Still leased to session 1, so it was not quietly put back up for grabs.
        assertThat(scheduler.leasedBlocks()).isEqualTo(1);
        assertThat(theirs.next(HAS_EVERYTHING).orElseThrow()).isNotEqualTo(held);
    }

    @Test
    void aDroppedPeerReturnsEveryBlockItWasHolding() {
        SwarmScheduler scheduler = scheduler();
        BlockSource leaving = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource staying = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);
        List<BlockPlan.Block> held = List.of(
                leaving.next(HAS_EVERYTHING).orElseThrow(),
                leaving.next(HAS_EVERYTHING).orElseThrow(),
                leaving.next(HAS_EVERYTHING).orElseThrow());
        BlockPlan.Block keptByTheOtherPeer = staying.next(HAS_EVERYTHING).orElseThrow();

        leaving.surrender();

        assertThat(scheduler.leasedBlocks()).isEqualTo(1);
        assertThat(drain(staying, HAS_EVERYTHING)).containsAll(held)
                .doesNotContain(keptByTheOtherPeer);
    }

    @Test
    void surrenderingTwiceDoesNotDuplicateWork() {
        SwarmScheduler scheduler = scheduler();
        BlockSource view = scheduler.viewFor(1, BLOCK_SIZE);
        everyoneHasEverything(1);
        view.next(HAS_EVERYTHING).orElseThrow();

        view.surrender();
        view.surrender();

        assertThat(scheduler.pendingBlocks()).isEqualTo(FULL_CHUNKS * BLOCKS_PER_CHUNK + 1);
        assertThat(new HashSet<>(drain(view, HAS_EVERYTHING)))
                .hasSize(FULL_CHUNKS * BLOCKS_PER_CHUNK + 1);
    }

    @Test
    void aChunkThatFailsItsHashIsRebuiltFromNothing() {
        SwarmScheduler scheduler = scheduler();
        BlockSource view = scheduler.viewFor(1, BLOCK_SIZE);
        everyoneHasEverything(1);
        List<BlockPlan.Block> chunkZero = drain(view, index -> index == 0);
        assertThat(chunkZero).hasSize(BLOCKS_PER_CHUNK);

        view.requeueChunk(0);

        assertThat(scheduler.wantedChunks()).contains(0);
        assertThat(drain(view, index -> index == 0)).containsExactlyElementsOf(chunkZero);
    }

    @Test
    void rebuildingAChunkDoesNotStrandBlocksAnotherPeerWasFetching() {
        SwarmScheduler scheduler = scheduler();
        BlockSource failed = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource other = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);
        failed.next(index -> index == 0).orElseThrow();
        other.next(index -> index == 0).orElseThrow();

        failed.requeueChunk(0);

        // Both old leases are void; the chunk is whole again in the queue.
        assertThat(scheduler.leasedBlocks()).isZero();
        assertThat(drain(other, index -> index == 0)).hasSize(BLOCKS_PER_CHUNK);
    }

    @Test
    void aChunkAnotherPeerAlreadyVerifiedIsNotRebuilt() throws Exception {
        SwarmScheduler scheduler = scheduler();
        BlockSource view = scheduler.viewFor(1, BLOCK_SIZE);
        everyoneHasEverything(1);
        drain(view, index -> index == 0);
        // Somebody else finished chunk 0 while our copy was still being hashed.
        cache(0);
        inventory.markStored(0);
        view.dropChunk(0);

        view.requeueChunk(0);

        assertThat(scheduler.wantedChunks()).doesNotContain(0);
    }

    @Test
    void aVerifiedChunkLeavesTheQueueForGood() {
        SwarmScheduler scheduler = scheduler();
        BlockSource view = scheduler.viewFor(1, BLOCK_SIZE);
        everyoneHasEverything(1);
        view.next(index -> index == 2).orElseThrow();

        view.dropChunk(2);

        assertThat(scheduler.wantedChunks()).doesNotContain(2);
        assertThat(scheduler.leasedBlocks()).isZero();
        assertThat(drain(view, HAS_EVERYTHING)).extracting(BlockPlan.Block::chunkIndex)
                .doesNotContain(2);
    }

    @Test
    void theSwarmIsOnlyDoneWhenNothingIsWaitingOrInFlight() {
        SwarmScheduler scheduler = scheduler();
        BlockSource view = scheduler.viewFor(1, BLOCK_SIZE);
        everyoneHasEverything(1);
        List<BlockPlan.Block> all = drain(view, HAS_EVERYTHING);

        // Everything is leased but nothing has verified yet.
        assertThat(view.isEmpty()).isFalse();
        assertThat(scheduler.isDone()).isFalse();

        for (int chunkIndex = 0; chunkIndex < CHUNKS; chunkIndex++) {
            view.dropChunk(chunkIndex);
        }

        assertThat(all).isNotEmpty();
        assertThat(scheduler.isDone()).isTrue();
        assertThat(view.isEmpty()).isTrue();
    }

    @Test
    void aSwarmSessionIsNeverTheSoleSourceSoRunningDryIsNotAFailure() {
        SwarmScheduler scheduler = scheduler();

        assertThat(scheduler.viewFor(1, BLOCK_SIZE).soleSource()).isFalse();
        assertThat(new BlockPlan(inventory, BLOCK_SIZE).soleSource()).isTrue();
    }

    @Test
    void aPeerThatCannotCarryAFullBlockIsRefused() {
        SwarmScheduler scheduler = scheduler();

        assertThat(scheduler.viewFor(1, BLOCK_SIZE).blockSize()).isEqualTo(BLOCK_SIZE);
        assertThat(scheduler.viewFor(2, BLOCK_SIZE * 4).blockSize()).isEqualTo(BLOCK_SIZE);
        assertThatThrownBy(() -> scheduler.viewFor(3, BLOCK_SIZE - 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("caps blocks at");
    }

    @Test
    void anAvailabilityMapForADifferentAssetIsRefused() {
        assertThatThrownBy(() -> new SwarmScheduler(inventory, new ChunkAvailability(CHUNKS + 1, SEED), BLOCK_SIZE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunks but the manifest has");
        assertThatThrownBy(() -> new SwarmScheduler(inventory, availability, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCompleteCacheStartsDone() throws Exception {
        for (int i = 0; i < CHUNKS; i++) {
            cache(i);
        }
        SwarmScheduler scheduler = scheduler();

        assertThat(scheduler.isDone()).isTrue();
        assertThat(scheduler.viewFor(1, BLOCK_SIZE).next(HAS_EVERYTHING)).isEmpty();
    }

    /**
     * One inventory is shared by the scheduler, the assembler, and every session, which
     * is what lets a chunk verified on one session disappear from everyone's queue.
     */
    private SwarmScheduler scheduler() {
        inventory.rescan();
        return new SwarmScheduler(inventory, availability, BLOCK_SIZE);
    }

    private void everyoneHasEverything(int... sessionIds) {
        byte[] everything = ChunkBitfield.empty(CHUNKS);
        for (int i = 0; i < CHUNKS; i++) {
            ChunkBitfield.set(everything, i);
        }
        for (int sessionId : sessionIds) {
            availability.join(sessionId, everything);
        }
    }

    private static void alternatingDrain(BlockSource first, BlockSource second, List<BlockPlan.Block> taken) {
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (BlockSource source : List.of(first, second)) {
                Optional<BlockPlan.Block> block = source.next(HAS_EVERYTHING);
                if (block.isPresent()) {
                    taken.add(block.get());
                    progressed = true;
                }
            }
        }
    }

    private static List<BlockPlan.Block> drain(BlockSource source, IntPredicate remoteHas) {
        List<BlockPlan.Block> taken = new ArrayList<>();
        Optional<BlockPlan.Block> next;
        while ((next = source.next(remoteHas)).isPresent()) {
            taken.add(next.get());
        }
        return taken;
    }

    private void cache(int chunkIndex) throws Exception {
        ChunkEntry chunk = manifest.chunks().get(chunkIndex);
        store.putVerified(chunk.sha256(), java.util.Arrays.copyOfRange(original,
                (int) chunk.offset(), (int) (chunk.offset() + chunk.length())));
    }

    private static byte[] bits(int... chunkIndexes) {
        byte[] bits = ChunkBitfield.empty(CHUNKS);
        for (int index : chunkIndexes) {
            ChunkBitfield.set(bits, index);
        }
        return bits;
    }

    private static byte[] deterministicBytes(int length) {
        byte[] out = new byte[length];
        new Random(20260913).nextBytes(out);
        return out;
    }
}
