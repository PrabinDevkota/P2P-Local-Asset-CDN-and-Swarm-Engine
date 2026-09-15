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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-04 acceptance: tail blocks may go to at most two sources, and the extra copy is
 * cancelled.
 *
 * <p>The problem being solved is the tail, not the average. Near the end there is less
 * work left than there are peers, so idle peers queue up behind whichever straggler holds
 * the last block, and one slow peer decides the finish time however fast the rest were.
 */
class SwarmEndgameTest {

    private static final int CHUNK_SIZE = 64;
    private static final int BLOCK_SIZE = 16;
    private static final int CHUNKS = 4;
    private static final int BLOCKS_PER_CHUNK = CHUNK_SIZE / BLOCK_SIZE;
    private static final int TOTAL_BLOCKS = CHUNKS * BLOCKS_PER_CHUNK;
    private static final long SEED = 20260915L;
    private static final IntPredicate HAS_EVERYTHING = index -> true;

    @TempDir
    Path tempDir;

    private byte[] original;
    private ReleaseManifest manifest;
    private ChunkStore store;
    private ChunkInventory inventory;
    private ChunkAvailability availability;
    private final List<Cancelled> cancelled = new ArrayList<>();

    @BeforeEach
    void publishAsset() throws Exception {
        original = deterministicBytes(CHUNKS * CHUNK_SIZE);
        Path published = Files.write(tempDir.resolve("game-x-1.4.0.bin"), original);
        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        manifest = ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                "2026-09-15T00:00:00Z", "2026-10-15T00:00:00Z", 1, "release-key-2026-01");
        store = new ChunkStore(tempDir.resolve("data"));
        inventory = new ChunkInventory(manifest, store);
        availability = new ChunkAvailability(CHUNKS, SEED);
    }

    @Test
    void awayFromTheTailNoBlockEverGoesToTwoPeers() {
        // Threshold of 2 against 16 blocks outstanding: nowhere near the tail.
        SwarmScheduler scheduler = scheduler(2);
        BlockSource first = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource second = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        BlockPlan.Block mine = first.next(HAS_EVERYTHING).orElseThrow();
        BlockPlan.Block theirs = second.next(HAS_EVERYTHING).orElseThrow();

        assertThat(scheduler.inEndgame()).isFalse();
        assertThat(theirs).isNotEqualTo(mine);
        assertThat(scheduler.duplicatedBlocks()).isZero();
        assertThat(scheduler.sourcesFor(mine)).isEqualTo(1);
    }

    @Test
    void theEndgameOpensOnlyAsTheQueueItselfGetsShort() {
        // pendingBlocks counts what is still owed, so it falls as chunks verify rather
        // than as blocks are handed out: peers being busy is not the same as being done.
        SwarmScheduler scheduler = scheduler(BLOCKS_PER_CHUNK);
        BlockSource only = scheduler.viewFor(1, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        drain(only);
        assertThat(scheduler.inEndgame()).isFalse();

        // Settle every chunk but the last, leaving exactly the threshold outstanding.
        for (int chunkIndex = 0; chunkIndex < CHUNKS - 1; chunkIndex++) {
            only.dropChunk(chunkIndex);
        }

        assertThat(scheduler.pendingBlocks()).isEqualTo(BLOCKS_PER_CHUNK);
        assertThat(scheduler.inEndgame()).isTrue();
        assertThat(scheduler.viewFor(2, BLOCK_SIZE).next(HAS_EVERYTHING))
                .isPresent()
                .get()
                .extracting(BlockPlan.Block::chunkIndex)
                .isEqualTo(CHUNKS - 1);
    }

    @Test
    void theLastFewBlocksMayBeInsuredWithASecondPeer() {
        SwarmScheduler scheduler = scheduler(TOTAL_BLOCKS);
        BlockSource straggler = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource helper = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        // One peer holds everything outstanding and is going nowhere.
        List<BlockPlan.Block> held = drain(straggler);
        assertThat(held).hasSize(TOTAL_BLOCKS);
        assertThat(helper.next(HAS_EVERYTHING)).isPresent();

        assertThat(scheduler.inEndgame()).isTrue();
        assertThat(scheduler.duplicatedBlocks()).isPositive();
    }

    @Test
    void twoSourcesIsTheCeilingNotAStartingPoint() {
        // Duplicating to everybody would turn the tail of every transfer into a
        // broadcast, which is what makes naive endgame handling worse than none.
        SwarmScheduler scheduler = scheduler(TOTAL_BLOCKS);
        BlockSource owner = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource insurer = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2, 3, 4);

        List<BlockPlan.Block> held = drain(owner);
        List<BlockPlan.Block> insured = drain(insurer);

        // Every block now has its two sources, so the ceiling is reached everywhere.
        assertThat(insured).containsExactlyInAnyOrderElementsOf(held);
        assertThat(scheduler.duplicatedBlocks()).isEqualTo(TOTAL_BLOCKS);
        assertThat(held).allSatisfy(block -> assertThat(scheduler.sourcesFor(block))
                .isEqualTo(SwarmScheduler.MAX_SOURCES_PER_BLOCK));

        // A third and fourth peer get nothing: two is a ceiling, not a starting point.
        assertThat(scheduler.viewFor(3, BLOCK_SIZE).next(HAS_EVERYTHING)).isEmpty();
        assertThat(scheduler.viewFor(4, BLOCK_SIZE).next(HAS_EVERYTHING)).isEmpty();
    }

    @Test
    void onePeerIsNeverAskedForTheSameBlockTwice() {
        SwarmScheduler scheduler = scheduler(TOTAL_BLOCKS);
        BlockSource only = scheduler.viewFor(1, BLOCK_SIZE);
        everyoneHasEverything(1);

        List<BlockPlan.Block> taken = drain(only);

        assertThat(taken).hasSize(TOTAL_BLOCKS).doesNotHaveDuplicates();
        assertThat(scheduler.duplicatedBlocks()).isZero();
    }

    @Test
    void theFirstCopyToArriveCancelsTheOther() {
        SwarmScheduler scheduler = scheduler(TOTAL_BLOCKS);
        BlockSource slow = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource quick = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        drain(slow);
        BlockPlan.Block raced = quick.next(HAS_EVERYTHING).orElseThrow();
        assertThat(scheduler.sourcesFor(raced)).isEqualTo(2);

        // The second peer wins the race.
        quick.completed(raced);

        assertThat(cancelled).containsExactly(new Cancelled(1, raced));
        assertThat(scheduler.sourcesFor(raced)).isEqualTo(1);
        assertThat(scheduler.duplicatedBlocks()).isZero();
    }

    @Test
    void theWinnerIsNotAskedToCancelItsOwnWork() {
        SwarmScheduler scheduler = scheduler(TOTAL_BLOCKS);
        BlockSource first = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource second = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        drain(first);
        BlockPlan.Block raced = second.next(HAS_EVERYTHING).orElseThrow();
        first.completed(raced);

        assertThat(cancelled).extracting(Cancelled::sessionId).containsExactly(2);
        assertThat(cancelled).noneMatch(entry -> entry.sessionId() == 1);
    }

    @Test
    void aDeliveredBlockIsNotHandedOutAgainForInsurance() {
        SwarmScheduler scheduler = scheduler(TOTAL_BLOCKS);
        BlockSource owner = scheduler.viewFor(1, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        List<BlockPlan.Block> held = drain(owner);
        for (BlockPlan.Block block : held) {
            owner.completed(block);
        }

        // Every outstanding block has already landed, so insuring any of them would buy
        // nothing and cost a duplicate transfer.
        assertThat(scheduler.viewFor(2, BLOCK_SIZE).next(HAS_EVERYTHING)).isEmpty();
        assertThat(cancelled).isEmpty();
    }

    @Test
    void aBlockGivenBackByOneSourceStaysWithTheOther() {
        SwarmScheduler scheduler = scheduler(TOTAL_BLOCKS);
        BlockSource first = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource second = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        drain(first);
        BlockPlan.Block shared = second.next(HAS_EVERYTHING).orElseThrow();
        int pendingBefore = scheduler.pendingBlocks();

        // The second peer times out on it. The first is still fetching it, so the block
        // is not idle and must not go back to the queue as if nobody had it.
        second.requeue(shared);

        assertThat(scheduler.sourcesFor(shared)).isEqualTo(1);
        assertThat(scheduler.pendingBlocks()).isEqualTo(pendingBefore);
        assertThat(scheduler.leasedBlocks()).isEqualTo(TOTAL_BLOCKS);
    }

    @Test
    void aBlockBothSourcesGiveUpOnGoesBackToTheQueue() {
        SwarmScheduler scheduler = scheduler(TOTAL_BLOCKS);
        BlockSource first = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource second = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        drain(first);
        BlockPlan.Block shared = second.next(HAS_EVERYTHING).orElseThrow();

        second.requeue(shared);
        first.requeue(shared);

        assertThat(scheduler.sourcesFor(shared)).isZero();
        assertThat(scheduler.viewFor(3, BLOCK_SIZE).next(HAS_EVERYTHING)).contains(shared);
    }

    @Test
    void aSecondSourceThatDiesDoesNotTakeTheBlockWithIt() {
        SwarmScheduler scheduler = scheduler(TOTAL_BLOCKS);
        BlockSource holder = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource doomed = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        drain(holder);
        BlockPlan.Block shared = doomed.next(HAS_EVERYTHING).orElseThrow();

        doomed.surrender();

        // The original source is still on it, so nothing was lost and nothing requeued.
        assertThat(scheduler.sourcesFor(shared)).isEqualTo(1);
        assertThat(scheduler.isDone()).isFalse();
    }

    @Test
    void aVerifiedChunkCancelsNothingBecauseItsBlocksAreGoneAlready() {
        SwarmScheduler scheduler = scheduler(TOTAL_BLOCKS);
        BlockSource first = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource second = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        drain(first);
        BlockPlan.Block shared = second.next(HAS_EVERYTHING).orElseThrow();
        first.dropChunk(shared.chunkIndex());

        assertThat(scheduler.sourcesFor(shared)).isZero();
        // completed() for a block whose chunk already settled must be a no-op, not a
        // cancel for a session that has long since moved on.
        second.completed(shared);
        assertThat(cancelled).isEmpty();
    }

    @Test
    void aChunkThatFailsItsHashDropsBothItsSources() {
        SwarmScheduler scheduler = scheduler(TOTAL_BLOCKS);
        BlockSource first = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource second = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        drain(first);
        BlockPlan.Block shared = second.next(HAS_EVERYTHING).orElseThrow();
        first.completed(shared);

        first.requeueChunk(shared.chunkIndex());

        assertThat(scheduler.sourcesFor(shared)).isZero();
        assertThat(scheduler.viewFor(3, BLOCK_SIZE).next(HAS_EVERYTHING))
                .isPresent()
                .get()
                .extracting(BlockPlan.Block::chunkIndex)
                .isEqualTo(shared.chunkIndex());
    }

    @Test
    void anEndgameSwarmStillFinishesWithEveryByteAccountedFor() {
        SwarmScheduler scheduler = scheduler(TOTAL_BLOCKS);
        BlockSource first = scheduler.viewFor(1, BLOCK_SIZE);
        BlockSource second = scheduler.viewFor(2, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        // Both peers work until nothing is left, duplicates and all.
        List<BlockPlan.Block> byFirst = drain(first);
        List<BlockPlan.Block> bySecond = drain(second);
        for (BlockPlan.Block block : byFirst) {
            first.completed(block);
        }
        for (int chunkIndex = 0; chunkIndex < CHUNKS; chunkIndex++) {
            first.dropChunk(chunkIndex);
        }

        assertThat(scheduler.isDone()).isTrue();
        assertThat(scheduler.inEndgame()).isFalse();
        // Every block of the asset was covered, and the duplicates were extra rather
        // than instead of.
        assertThat(byFirst).hasSize(TOTAL_BLOCKS).doesNotHaveDuplicates();
        assertThat(bySecond).allMatch(byFirst::contains);
    }

    @Test
    void endgameOffMeansOneSourceRightToTheLastBlock() {
        SwarmScheduler scheduler = scheduler(SwarmScheduler.NO_ENDGAME);
        BlockSource only = scheduler.viewFor(1, BLOCK_SIZE);
        everyoneHasEverything(1, 2);

        drain(only);

        assertThat(scheduler.inEndgame()).isFalse();
        assertThat(scheduler.viewFor(2, BLOCK_SIZE).next(HAS_EVERYTHING)).isEmpty();
        assertThat(scheduler.duplicatedBlocks()).isZero();
    }

    @Test
    void aNegativeThresholdIsRejected() {
        assertThatThrownBy(() -> new SwarmScheduler(inventory, availability, BLOCK_SIZE, -1,
                SwarmScheduler.DuplicateCanceller.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endgameThreshold");
    }

    private SwarmScheduler scheduler(int endgameThreshold) {
        inventory.rescan();
        return new SwarmScheduler(inventory, availability, BLOCK_SIZE, endgameThreshold,
                (sessionId, block) -> cancelled.add(new Cancelled(sessionId, block)));
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

    private static List<BlockPlan.Block> drain(BlockSource source) {
        List<BlockPlan.Block> taken = new ArrayList<>();
        Optional<BlockPlan.Block> next;
        while ((next = source.next(HAS_EVERYTHING)).isPresent()) {
            taken.add(next.get());
        }
        return taken;
    }

    private static byte[] deterministicBytes(int length) {
        byte[] out = new byte[length];
        new Random(20260915).nextBytes(out);
        return out;
    }

    private record Cancelled(int sessionId, BlockPlan.Block block) {
    }
}
