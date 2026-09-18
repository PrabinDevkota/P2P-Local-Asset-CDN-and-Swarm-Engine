package com.prabin.swarmedge.peer.chunk;

import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.protocol.ProtocolViolationException;
import com.prabin.swarmedge.protocol.msg.ChunkBitfield;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkInventoryTest {

    private static final int CHUNK_SIZE = 64;
    private static final int TAIL_BYTES = 10;
    private static final int FULL_CHUNKS = 5;

    @TempDir
    Path tempDir;

    private byte[] original;
    private ReleaseManifest manifest;
    private ChunkStore store;

    @BeforeEach
    void publishAsset() throws Exception {
        original = deterministicBytes(FULL_CHUNKS * CHUNK_SIZE + TAIL_BYTES);
        Path published = Files.write(tempDir.resolve("game-x-1.4.0.bin"), original);
        List<ChunkEntry> chunks = new FileChunker(CHUNK_SIZE).chunk(published);
        manifest = ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, CHUNK_SIZE, chunks,
                "2026-09-13T00:00:00Z", "2026-10-13T00:00:00Z", 1, "release-key-2026-01");
        store = new ChunkStore(tempDir.resolve("data"));
    }

    @Test
    void anEmptyCacheAdvertisesNothing() {
        ChunkInventory inventory = new ChunkInventory(manifest, store);

        assertThat(inventory.chunkCount()).isEqualTo(FULL_CHUNKS + 1);
        assertThat(inventory.complete()).isFalse();
        assertThat(inventory.missing()).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(HexFormat.of().formatHex(inventory.bitfield())).isEqualTo("00");
    }

    @Test
    void theBitfieldNamesExactlyTheChunksOnDisk() throws Exception {
        cache(0);
        cache(2);
        cache(5);
        ChunkInventory inventory = new ChunkInventory(manifest, store);

        byte[] bits = inventory.bitfield();

        ChunkBitfield.validate(bits, inventory.chunkCount());
        assertThat(HexFormat.of().formatHex(bits)).isEqualTo("a4");
        assertThat(inventory.has(0)).isTrue();
        assertThat(inventory.has(1)).isFalse();
        assertThat(inventory.missing()).containsExactly(1, 3, 4);
    }

    @Test
    void aFullCacheIsCompleteAndHasNoPaddingBitsSet() throws Exception {
        for (int i = 0; i <= FULL_CHUNKS; i++) {
            cache(i);
        }
        ChunkInventory inventory = new ChunkInventory(manifest, store);

        byte[] bits = inventory.bitfield();

        ChunkBitfield.validate(bits, inventory.chunkCount());
        assertThat(inventory.complete()).isTrue();
        assertThat(inventory.missing()).isEmpty();
        // Six chunks in eight bits: the last two must stay clear.
        assertThat(HexFormat.of().formatHex(bits)).isEqualTo("fc");
    }

    @Test
    void aRestartRebuildsTheBitfieldFromTheIndexAndTheFiles() throws Exception {
        try (com.prabin.swarmedge.manifest.ChunkIndex index =
                     com.prabin.swarmedge.manifest.ChunkIndex.open(tempDir.resolve("cache.db"))) {
            store = new ChunkStore(tempDir.resolve("data"), index);
            cache(0);
            cache(2);
            cache(5);
        }
        try (com.prabin.swarmedge.manifest.ChunkIndex index =
                     com.prabin.swarmedge.manifest.ChunkIndex.open(tempDir.resolve("cache.db"))) {
            ChunkStore restarted = new ChunkStore(tempDir.resolve("data"), index);
            ChunkInventory inventory = new ChunkInventory(manifest, restarted);

            assertThat(inventory.has(0)).isTrue();
            assertThat(inventory.has(1)).isFalse();
            assertThat(inventory.has(2)).isTrue();
            assertThat(inventory.has(5)).isTrue();
            assertThat(inventory.missing()).containsExactly(1, 3, 4);
        }
    }

    @Test
    void anUnverifiedFileIsNotAdvertisedEvenIfItIsStillOnDisk() throws Exception {
        try (com.prabin.swarmedge.manifest.ChunkIndex index =
                     com.prabin.swarmedge.manifest.ChunkIndex.open(tempDir.resolve("cache.db"))) {
            store = new ChunkStore(tempDir.resolve("data"), index);
            cache(0);
            Files.write(store.pathFor(manifest.chunks().get(0).sha256()), new byte[] {9, 9});
            assertThatThrownBy(() -> store.read(manifest.chunks().get(0).sha256()))
                    .isInstanceOf(IllegalArgumentException.class);

            ChunkInventory inventory = new ChunkInventory(manifest, store);

            assertThat(store.contains(manifest.chunks().get(0).sha256())).isTrue();
            assertThat(inventory.has(0)).isFalse();
            assertThat(inventory.missing()).contains(0);
        }
    }

    @Test
    void whatWeHoldIsReadOnceSoNoRequestHasToTouchTheDisk() throws Exception {
        ChunkInventory inventory = new ChunkInventory(manifest, store);
        cache(1);

        // Caching behind the inventory's back is invisible on purpose: a handler asking
        // "do we have chunk 1?" must never turn into a blocking filesystem call.
        assertThat(inventory.has(1)).isFalse();

        inventory.rescan();

        assertThat(inventory.has(1)).isTrue();
    }

    @Test
    void aChunkThatJustVerifiedIsAdvertisedWithoutRereadingTheStore() {
        ChunkInventory inventory = new ChunkInventory(manifest, store);

        inventory.markStored(3);

        assertThat(inventory.has(3)).isTrue();
        assertThat(inventory.missing()).containsExactly(0, 1, 2, 4, 5);
        assertThat(HexFormat.of().formatHex(inventory.bitfield())).isEqualTo("10");
        ChunkBitfield.validate(inventory.bitfield(), inventory.chunkCount());
    }

    @Test
    void theAdvertisedBitfieldCannotBeEditedThroughTheCopyWeHandOut() {
        ChunkInventory inventory = new ChunkInventory(manifest, store);

        byte[] handedOut = inventory.bitfield();
        ChunkBitfield.set(handedOut, 0);

        assertThat(inventory.has(0)).isFalse();
        assertThat(inventory.bitfield()).containsExactly(new byte[]{0});
    }

    @Test
    void aChunkIndexOutsideTheManifestIsAProtocolViolation() {
        ChunkInventory inventory = new ChunkInventory(manifest, store);

        assertThatThrownBy(() -> inventory.chunk(6))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> inventory.chunk(-1))
                .isInstanceOf(ProtocolViolationException.class);
        assertThatThrownBy(() -> inventory.has(99))
                .isInstanceOf(ProtocolViolationException.class);
        // Chunk 6 is a padding bit in the same byte, not a chunk, so it must not read false.
        assertThatThrownBy(() -> inventory.has(6))
                .isInstanceOf(ProtocolViolationException.class);
        assertThatThrownBy(() -> inventory.markStored(6))
                .isInstanceOf(ProtocolViolationException.class);
    }

    @Test
    void aRequestMayNotReadPastTheEndOfItsChunk() {
        ChunkInventory inventory = new ChunkInventory(manifest, store);

        assertThat(inventory.requireInRange(0, 0, CHUNK_SIZE).length()).isEqualTo(CHUNK_SIZE);
        assertThat(inventory.requireInRange(0, 32, 32).index()).isZero();

        assertThatThrownBy(() -> inventory.requireInRange(0, 1, CHUNK_SIZE))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("leaves chunk");
        assertThatThrownBy(() -> inventory.requireInRange(0, CHUNK_SIZE, 1))
                .isInstanceOf(ProtocolViolationException.class);
    }

    @Test
    void theShortFinalChunkIsBoundedByItsOwnLengthNotTheChunkSize() {
        ChunkInventory inventory = new ChunkInventory(manifest, store);

        assertThat(inventory.chunk(FULL_CHUNKS).length()).isEqualTo(TAIL_BYTES);
        assertThat(inventory.requireInRange(FULL_CHUNKS, 0, TAIL_BYTES)).isNotNull();

        assertThatThrownBy(() -> inventory.requireInRange(FULL_CHUNKS, 0, TAIL_BYTES + 1))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("leaves chunk");
    }

    @Test
    void anEmptySpanIsRejectedRatherThanTreatedAsANoOp() {
        ChunkInventory inventory = new ChunkInventory(manifest, store);

        assertThatThrownBy(() -> inventory.requireInRange(0, 0, 0))
                .isInstanceOf(ProtocolViolationException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> inventory.requireInRange(0, -1, 8))
                .isInstanceOf(ProtocolViolationException.class);
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
