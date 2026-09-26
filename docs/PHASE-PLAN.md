# Next-phase plan (file-by-file)

Task IDs are the blueprint's backlog IDs (§16). `./mvnw verify` is the gate for every step.

Phases 0–12 are complete. The release checklist is `docs/RELEASE.md`.

## What exists

| Module | What works |
| --- | --- |
| `common/` | `AssetId`, `PeerId`, `Hex`, `Defaults` (4 MiB chunk, 256 KiB block, 45 s TTL, limit 20) |
| `protocol/` | Frame codecs + golden vectors for all ten message types, streaming BLOCK decoder, pinned BITFIELD bit order |
| `manifest-tool/` | Chunk → `ChunkStore` + SQLite `ChunkIndex` → unsigned manifest → Ed25519 sign → verify → freshness/sequence → rebuild → resume; CLI `gen-key` / `sign` / `verify` |
| `origin-fixture/` | `GET /files/{name}` 200/206/404, `GET /manifests/{name}.json` copy only, served-byte ledger plus a 1 s peak per run |
| `peer-agent/` | Origin, swarm, LAPS, `HybridDownloader`, and `PeerQuarantine` eligibility |
| `tracker-service/` | Announce with bearer peer token, Redis `HEXPIRE` 45 s, ranked candidates, `POST /api/v1/auth/peer-token`, announce rate limit |
| `benchmark-runner/` | `B0Runner`, `SwarmRunner`, `SecurityOverheadRunner`, and `CrossVersionStudy` with B0–B6 and B9 configs |

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

## Phase 5 — Basic swarm, baseline B1 (`peer-agent/`) — complete

Phase 4 proved one connection. Phase 5 was about many, and every new problem was a question of choice: which chunk to want next, which peer to ask for it, and how to stop eight sessions from fetching the same block.

| Step | File | Outcome |
| --- | --- | --- |
| P5-01 | `ChunkAvailability.java` | BITFIELD on join and HAVE mid-session fold into one holder count per chunk; a dropped peer takes its whole inventory with it |
| P5-02 | `ChunkAvailability.rarestFirst` | Scarcest wanted chunk first, ties broken on a seed so an even swarm still replays in order |
| P5-03 | `SwarmScheduler.java`, `BlockSource.java` | One shared queue behind a per-session view; a block is leased to exactly one peer, 8 outstanding each |
| P5-04 | `SwarmRunner.java`, `b1-basic-swarm.yaml` | Eight loopback seeders, repeats agree on the asset hash |
| P5-05 | `SwarmRunnerTest.java` | Peers killed mid-transfer at 10 % and 25 %; their blocks are re-queued and the swarm still finishes |

Supporting work: `SwarmDownloader` dials candidates and owns the sessions, `SessionEvents` is how a `LeecherHandler` reports what it learned and what it stored, and `BlockPlan` now implements `BlockSource` so the single-peer path is unchanged.

From §8.3, the pipeline rules that hold: a block is never scheduled twice in normal mode, and a timeout or disconnect returns the block to the scheduler without invalidating verified chunks. Endgame duplicates are explicitly **not** here — that is P6-04, and neither is the global outstanding-byte cap.

Rules carried forward: no blocking hash, file, or SQLite work on a Netty event loop; a chunk is advertised only after it verifies; the request budget stays the memory budget.

## Phase 6 — Locality + LAPS (`common/`, `peer-agent/`, `tracker-service/`) — complete

Phase 5 answered decision A — *which chunk next* — with rarest-first. Phase 6 answers decision B: *which peer to ask*. The swarm currently dials candidates in whatever order the tracker handed them over, so locality is a tracker-side ranking and nothing the agent acts on.

Blueprint backlog (§16), verbatim tasks with their own acceptance tests:

| Step | Job | Acceptance |
| --- | --- | --- |
| P6-01 | Site/network-group config | Tracker and peers expose a locality class without `/24` hard-coding |
| P6-02 | EWMA RTT / goodput measurement | Per-peer state updates only from observed transfer or ping data |
| P6-03 | LAPS score implementation | Tests cover normalization, missing metrics, tie-breaking, weight config |
| P6-04 | Endgame duplicate + cancel | Tail blocks may go to at most two sources; the extra copy is cancelled |
| P6-05 | B1/B2/B3 experiment configs | Same assets, seeds, and network profiles, so only the scheduler changes |

The score is fixed by §8.2 and is deliberately explainable rather than learned:

```
peerScore = wL*locality + wT*throughput + wR*rtt + wC*capacity + wH*health
            wL=0.35  wT=0.30  wR=0.15  wC=0.10  wH=0.10   (defaults, not claimed optimal)
```

`locality` is 1.0 for the same network group, 0.7 for the same site, 0.2 for a remote site. `throughput` is EWMA goodput normalized within the current candidate set, `rtt` is inverse-normalized EWMA RTT, `capacity` is `1 - normalized activeUploadLoad`, and `health` is recent success against timeouts and protocol failures. Weights are configuration because the paper has to sensitivity-test them; a weight baked into code is not a variable.

Two rules that matter more than the formula. A metric is only written from something observed — a completed transfer or a PONG — never from a number a peer asserts about itself, or the score becomes a self-report. And LAPS reorders sources; it never overrides verification. A chunk from the best-scoring peer in the swarm is still refused if its hash is wrong.

All five are done. Live sessions now record into `PeerMetrics`, and the scheduler prefers a better-scoring source while that source still has pipeline room, so a B3 run can differ from B2 during the transfer itself. `research/configs/*.yaml` carry an identical `topology` section so B2/B3 have labels to score with without moving any other experimental variable.

## Phase 7 — Persistent cache and cross-version reuse (`peer-agent/`, `manifest-tool/`)

Blueprint backlog (§16):

| Step | Job | Acceptance |
| --- | --- | --- |
| P7-01 | Cache lookup before network | A verified local chunk satisfies a request with no network bytes |
| P7-02 | LRU / quota / min-free-space eviction | Pinned or referenced chunks are not evicted incorrectly |
| P7-03 | Warm-start inventory | An agent restart rebuilds its bitfield from the cache index and files |
| P7-04 | B4 warm-cache experiment | Peer and origin byte accounting shows reuse, with no invented target |

The store and its SQLite index already existed from P1-04. What this phase added is the policy that uses them.

All four are done. `ChunkStore.hasVerified` is the lookup before network; `CacheEvictor` deletes LRU unreferenced chunks down to quota and min-free-space without touching pinned rows; a restart rebuilds the bitfield from the index plus file existence; `b4-warm-cache.yaml` is B0 with a warm store so origin and cache bytes can be compared.

## Phase 8 — EDGE role + progressive fallback (`peer-agent/`) — complete

| Step | Job | Outcome |
| --- | --- | --- |
| P8-01 | EDGE role + upload budget | Desktop 32/2/unlimited; EDGE 128/8/rate; healthy same-site EDGE ranks first |
| P8-02 | Timers + jitter | `FallbackPolicy.delay` is deterministic; 100 seeds do not share one origin timestamp |
| P8-03 | Cross-source cancel | Swarm hit cancels that origin GET; origin hit drops the chunk from the peer queue |
| P8-04 | B6 scenarios | Same asset/seed as B3; runner records origin, peak, EDGE, peer, and cache bytes |

EDGE is the same binary. Role is not on the tracker wire. Origin stays chunk-granular. B1–B3 configs were not edited.

## Phase 9 — Security hardening — complete

| Step | Job | Outcome |
| --- | --- | --- |
| P9-01 | Manifest freshness/sequence | `ReleaseFreshness` after verify; expired and rollback are explicit `StaleReleaseException`s |
| P9-02 | Peer reputation/quarantine | Hash/protocol failures drop eligibility; SHA-256 still runs on every assembled chunk |
| P9-03 | Secret/log audit | `SecretLogAuditTest` plus [`docs/SECURITY.md`](SECURITY.md) |
| P9-04 | Security overhead | B9 YAML + runner records hash/sign/verify/HMAC nanos and claims none of them |

Announce rate limiting (`rate:{peerId}:announce`) landed here as the carried-forward abuse control. A burst is HTTP 429; other peers still announce.

## Phase 10 — Experiment harness — complete

| Step | Job | Outcome |
| --- | --- | --- |
| P10-01 | Scenario YAML schema | `PaperScenario` requires baseline, scale, network, churn, cache, seed, repetitions |
| P10-02 | netem + fault injection | `TcNetem` applies and `close()` removes the qdisc, including after a failed run |
| P10-03 | Immutable raw results | `RunFolder` writes the §13.3 files and refuses to overwrite a passed run |
| P10-04 | Reproduce script | `scripts/reproduce_paper.ps1` writes `research/processed/b0-smoke-table.md` |

Actuator `GET /actuator/health` and `GET /actuator/prometheus` are on the tracker. Client counts 10, 25, and 50 are named and `ClientFanout` runs that many tasks. A full-asset download at those counts is not part of `./mvnw verify`.

## Phase 11 — FastCDC — complete

| Step | Job | Outcome |
| --- | --- | --- |
| P11-01 | `Chunker` | Fixed and FastCDC catalogs are ordered `ChunkEntry` rows. Transfer still uses `BlockPlan` |
| P11-02 | Version fixture | Insert, delete, and replace are exact. The edit is asserted |
| P11-03 | B4 vs B5 | Shared bytes, manifest size, chunking time, elapsed time, and a 95% bootstrap interval. No ranking |

## Phase 12 — Release — complete

| Step | Job | Outcome |
| --- | --- | --- |
| P12-01 | Demo profile + dashboard | `DemoRun` plus `infra/docker-compose.demo.yml` |
| P12-02 | README | Status through Phase 12 and the demo command |
| P12-03 | Paper pack | `research/analysis/index_runs.py` maps configs to raw summaries |
| P12-04 | Release checklist | `docs/RELEASE.md` |

No invented Mbps, offload %, or dedup ratio.

## Working rule

One production file (or one test file) per commit. Tests must pass before the next file.
