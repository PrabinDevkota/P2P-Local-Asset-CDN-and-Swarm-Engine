# Next-phase plan (file-by-file)

Task IDs are the blueprint's backlog IDs (§16). `./mvnw verify` is the gate for every step.

Phases 0–4 are complete. **Phase 5 (basic swarm, baseline B1) is next.**

## What exists

| Module | What works |
| --- | --- |
| `common/` | `AssetId`, `PeerId`, `Hex`, `Defaults` (4 MiB chunk, 256 KiB block, 45 s TTL, limit 20) |
| `protocol/` | Frame codecs + golden vectors for all ten message types, streaming BLOCK decoder, pinned BITFIELD bit order |
| `manifest-tool/` | Chunk → `ChunkStore` + SQLite `ChunkIndex` → unsigned manifest → Ed25519 sign → verify → rebuild → resume; CLI `gen-key` / `sign` / `verify` |
| `origin-fixture/` | `GET /files/{name}` 200/206/404, `GET /manifests/{name}.json` copy only, served-byte ledger per run |
| `peer-agent/` | `OriginDownloader` (B0 path) plus a live one-to-one Netty session: seeder serves, leecher fetches and verifies |
| `tracker-service/` | Announce with bearer peer token, Redis `HEXPIRE` 45 s, ranked candidates, `POST /api/v1/auth/peer-token` |
| `benchmark-runner/` | `B0Runner` + `B0ScenarioConfig` reading `research/configs/*.yaml` |

## Phase 4 — Two-peer Netty session (`peer-agent/`) — complete

| Step | File | Outcome |
| --- | --- | --- |
| P4-01 | `SessionState.java`, `PeerSession.java` | HELLO → HELLO_ACK → BITFIELD → ACTIVE; any failure goes straight to CLOSED |
| P4-02 | `StreamingFrameDecoder.java` | Partial, coalesced, and back-to-back frames; length checked before anything is sized from it |
| P4-03 | `ChunkAssembler.java`, `ByteRanges.java` | Blocks land at their offset in a staging file; the chunk is hashed from disk, never from heap |
| P4-04 | `BlockSender.java` | Metadata header then `FileRegion`, with the buffered fallback tested alongside it |
| P4-05 | `RequestTracker.java` | CANCEL drops queued work, timeouts re-queue, late data is ignored rather than treated as hostile |
| P4-06 | `ProtocolFuzzTest.java`, `SeederBoundsTest.java` | Random noise, every single-bit flip, and hostile field values close the connection and leave the seeder usable |

Supporting work: `ChunkStore.putVerifiedFile` commits a staging file by streaming its hash, `ChunkInventory` answers what we hold and what a REQUEST may touch, `BlockPlan` owns the request queue, and `SeederServer` / `LeecherClient` bootstrap the two sides.

## Phase 5 — Basic swarm, baseline B1 (`peer-agent/`)

Phase 4 proved one connection. Phase 5 is about many, and every new problem is a question of choice: which chunk to want next, which peer to ask for it, and how to stop eight sessions from fetching the same block.

Blueprint backlog (§16) with its own acceptance tests:

| Step | Job | Acceptance |
| --- | --- | --- |
| P5-01 | Bitfield / HAVE inventory aggregation | A newly verified chunk is immediately discoverable to connected peers |
| P5-02 | Rarest-first selection | A deterministic fixture picks the minimum-availability chunk; the tie-break is seeded |
| P5-03 | Request pipeline, 8 per peer | Several requests outstanding at once; no stop-and-wait |
| P5-04 | Eight-peer scenario | Every peer completes and its output hashes match the manifest |
| P5-05 | Churn smoke at 10% and 25% | A dropped peer's blocks are re-queued and the swarm still finishes while coverage holds |

From §8.3, the pipeline rules that go with it: a block is never scheduled twice in normal mode, a timeout or disconnect returns the block to the scheduler without invalidating verified chunks, and outstanding bytes are capped per peer and globally. Endgame duplicates are explicitly **not** here — that is P6-04.

Rules carried forward: no blocking hash, file, or SQLite work on a Netty event loop; a chunk is advertised only after it verifies; the request budget stays the memory budget.

## Carried forward (not blocking Phase 5)

These are blueprint items whose phase is closed but which later phases assume.

| Item | Blueprint ref | Where it lands |
| --- | --- | --- |
| Actuator health + Prometheus on the tracker | §10.1 | Phase 10 observability |
| Announce rate limiting (`rate:{peerId}:announce`) | §10.2, §11.2 | Phase 9 abuse controls |
| `capabilities` / `uploadBudget` on announce | §10.3 | Phase 6, LAPS needs the capacity hint |
| Immutable raw run folders under `research/raw/` | §13.3 | P10-03 |
| Cache eviction acting on `ChunkIndex` candidates | §9.1 | P7-02 |
| `docs/architecture.md`, `docs/experiment-method.md` | §14 | Before the paper draft |

## Later (do not pull forward)

- Phase 6 locality + LAPS (B2/B3), endgame duplicate + cancel
- Phase 7 persistent cache B4, eviction, warm start
- Phase 8 EDGE role + progressive fallback with jitter
- Phase 9 security hardening (sequence/rollback, reputation, log audit)
- Phase 10 experiment harness; Phase 11 FastCDC; Phase 12 release
- No invented Mbps or offload %

## Working rule

One production file (or one test file) per commit. Tests must pass before the next file.
