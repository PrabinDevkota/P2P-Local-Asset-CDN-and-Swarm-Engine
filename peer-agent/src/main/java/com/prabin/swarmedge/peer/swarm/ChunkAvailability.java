package com.prabin.swarmedge.peer.swarm;

import com.prabin.swarmedge.protocol.msg.ChunkBitfield;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.IntPredicate;

/**
 * How many connected peers hold each chunk (blueprint P5-01, §8.1 decision A).
 *
 * <p>This is the only thing rarest-first needs to know. A peer contributes its BITFIELD
 * when it joins, adds chunks as it announces HAVE, and takes its whole contribution away
 * when the connection drops — so scarcity always describes peers we can actually reach,
 * not peers we once met.
 *
 * <p>Ties are broken by a seeded permutation rather than by chunk index. With eight
 * leechers starting at once, every count is equal at the beginning, and index order
 * would send all of them after chunk 0. A seed keeps that spread reproducible, which is
 * what makes a scheduling experiment worth re-running.
 *
 * <p>Shared by every session in one swarm, so every method is synchronized. The critical
 * sections are counter arithmetic; contention at eight peers is not a concern.
 */
public final class ChunkAvailability {

    private final int chunkCount;
    private final int[] holders;
    private final int[] tieRank;
    private final Map<Integer, byte[]> advertised = new HashMap<>();

    public ChunkAvailability(int chunkCount, long tieBreakSeed) {
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount must be non-negative");
        }
        this.chunkCount = chunkCount;
        this.holders = new int[chunkCount];
        this.tieRank = seededRanks(chunkCount, tieBreakSeed);
    }

    public int chunkCount() {
        return chunkCount;
    }

    /**
     * Record what a session's BITFIELD claims. Replaces any earlier claim from the same
     * session, so a reconnect cannot count twice.
     */
    public synchronized void join(int sessionId, byte[] bitfield) {
        Objects.requireNonNull(bitfield, "bitfield");
        ChunkBitfield.validate(bitfield, chunkCount);
        leave(sessionId);
        byte[] copy = bitfield.clone();
        advertised.put(sessionId, copy);
        for (int i = 0; i < chunkCount; i++) {
            if (ChunkBitfield.get(copy, i)) {
                holders[i]++;
            }
        }
    }

    /** Record a HAVE. Ignores a chunk this session already claimed, so counts stay true. */
    public synchronized void note(int sessionId, int chunkIndex) {
        requireKnownChunk(chunkIndex);
        byte[] bits = advertised.get(sessionId);
        if (bits == null) {
            throw new IllegalStateException("session " + sessionId + " announced HAVE before BITFIELD");
        }
        if (ChunkBitfield.get(bits, chunkIndex)) {
            return;
        }
        ChunkBitfield.set(bits, chunkIndex);
        holders[chunkIndex]++;
    }

    /** Forget a session. Idempotent: a peer can drop before it ever sent a BITFIELD. */
    public synchronized void leave(int sessionId) {
        byte[] bits = advertised.remove(sessionId);
        if (bits == null) {
            return;
        }
        for (int i = 0; i < chunkCount; i++) {
            if (ChunkBitfield.get(bits, i)) {
                holders[i]--;
            }
        }
    }

    public synchronized int holders(int chunkIndex) {
        requireKnownChunk(chunkIndex);
        return holders[chunkIndex];
    }

    public synchronized boolean reachable(int chunkIndex) {
        return holders(chunkIndex) > 0;
    }

    public synchronized int connectedPeers() {
        return advertised.size();
    }

    /**
     * Wanted chunks, scarcest first, skipping any that no connected peer holds.
     *
     * @param wanted which chunks the caller still needs
     */
    public synchronized List<Integer> rarestFirst(IntPredicate wanted) {
        Objects.requireNonNull(wanted, "wanted");
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            if (holders[i] > 0 && wanted.test(i)) {
                candidates.add(i);
            }
        }
        candidates.sort((left, right) -> {
            int byScarcity = Integer.compare(holders[left], holders[right]);
            return byScarcity != 0 ? byScarcity : Integer.compare(tieRank[left], tieRank[right]);
        });
        return List.copyOf(candidates);
    }

    /**
     * A fixed shuffle of chunk indexes. Two peers built with the same seed agree on the
     * order, and a run can be replayed exactly.
     */
    private static int[] seededRanks(int chunkCount, long seed) {
        int[] ranks = new int[chunkCount];
        for (int i = 0; i < chunkCount; i++) {
            ranks[i] = i;
        }
        Random random = new Random(seed);
        for (int i = chunkCount - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = ranks[i];
            ranks[i] = ranks[j];
            ranks[j] = swap;
        }
        return ranks;
    }

    private void requireKnownChunk(int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException(
                    "chunkIndex out of range: " + chunkIndex + " of " + chunkCount);
        }
    }
}
