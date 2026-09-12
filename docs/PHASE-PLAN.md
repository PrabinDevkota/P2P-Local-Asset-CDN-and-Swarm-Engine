# Next-phase plan (file-by-file)

Task IDs are the blueprint's backlog IDs (§16). `./mvnw verify` is the gate for every step.

Phases 0–3 are complete. **Phase 4 (two-peer Netty data plane) is next.**

## What exists

| Module | What works |
| --- | --- |
| `common/` | `AssetId`, `PeerId`, `Hex`, `Defaults` (4 MiB chunk, 256 KiB block, 45 s TTL, limit 20) |
| `protocol/` | Frame codecs + golden vectors for all ten message types. No live session yet |
| `manifest-tool/` | Chunk → `ChunkStore` + SQLite `ChunkIndex` → unsigned manifest → Ed25519 sign → verify → rebuild → resume; CLI `gen-key` / `sign` / `verify` |
| `origin-fixture/` | `GET /files/{name}` 200/206/404, `GET /manifests/{name}.json` copy only, served-byte ledger per run |
| `peer-agent/` | `OriginDownloader` (B0 path). No Netty session yet |
| `tracker-service/` | Announce with bearer peer token, Redis `HEXPIRE` 45 s, ranked candidates, `POST /api/v1/auth/peer-token` |
| `benchmark-runner/` | `B0Runner` + `B0ScenarioConfig` reading `research/configs/*.yaml` |

## Phase 4 — Two-peer Netty session (`peer-agent/`)

Use the existing `protocol/` codecs and the existing `ChunkStore`. Still no LAPS, no rarest-first, no tracker-driven peer choice.

| Step | File | Job |
| --- | --- | --- |
| P4-01 | `PeerSession.java` | Server/client bootstrap + session state: HELLO → HELLO_ACK → BITFIELD |
| P4-02 | `FrameDecoder` hardening | Max lengths; partial and coalesced frames decode correctly |
| P4-03 | `BlockReceiver.java` | Streaming BLOCK receive; no whole chunk buffered in heap |
| P4-04 | `BlockSender.java` | Header + `FileRegion` send path; wire capture sees metadata then exact data |
| P4-05 | `RequestTracker.java` | CANCEL and timeout; late data ignored safely |
| P4-06 | `ProtocolFuzzTest.java` | Bad lengths and indices never crash or allocate unbounded |

Rules for this phase: no blocking hash, file, or SQLite work on a Netty event loop; a block is accepted only after the whole parent chunk hashes; an unverified chunk is never advertised in BITFIELD or HAVE.

## Carried forward (not blocking Phase 4)

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

- Phase 5 basic swarm B1 (rarest-first, 8 peers)
- Phase 6 locality + LAPS (B2/B3), endgame duplicate + cancel
- Phase 7 persistent cache B4, eviction, warm start
- Phase 8 EDGE role + progressive fallback with jitter
- Phase 9 security hardening (sequence/rollback, reputation, log audit)
- Phase 10 experiment harness; Phase 11 FastCDC; Phase 12 release
- No invented Mbps or offload %

## Working rule

One production file (or one test file) per commit. Tests must pass before the next file.
