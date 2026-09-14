# Status

Blueprint source of truth: [`docs/P2P_Local_Asset_CDN_Implementation_and_Research_Blueprint.docx`](P2P_Local_Asset_CDN_Implementation_and_Research_Blueprint.docx), vendored into the repository so a clean clone carries its own contract.

Task IDs below are the blueprint's own backlog (§16).

Legend: `[x]` done, `[~]` partial, `[ ]` not started.

## Phase 0 — Architecture freeze — done

- [x] P0-01 Maven multi-module skeleton + wrapper (`./mvnw verify`)
- [x] P0-02 Manifest v1 schema + Java records + canonical JSON tests
- [x] P0-03 `protocol-v1.md` + codec golden vectors
- [x] P0-04 Threat model + ADR-001..004
- [x] P0-05 CI (Java 21 Temurin, `./mvnw -B verify`)

## Phase 1 — Local content engine — done

- [x] P1-01 Fixed chunker on `FileChannel` (0 B, 1 B, exact multiple, remainder)
- [x] P1-02 SHA-256 chunk catalog (`FileChunker` → `AssetIngestor`)
- [x] P1-03 Sign / verify CLI (`gen-key`, `sign`, `verify`; wrong key exits non-zero)
- [x] P1-04 `ChunkStore` + SQLite `ChunkIndex` (`cache.db`: length, verified, lastAccess, refCount, storedAt)
- [x] P1-05 `AssetMaterializer` + restart resume (`AssetResumeTest`: verified chunks are not rewritten)

A row reaches the index only after the bytes hashed and the file was committed, so staging can never look cached. A chunk that fails a re-hash is marked unverified and stops counting as cached. See [ADR-005](adr/ADR-005-chunk-index.md).

Not covered: a `>2 GiB` chunker case, and eviction itself (the index exposes candidates; Phase 7 acts on them).

## Phase 2 — Origin baseline (B0) — done

- [x] P2-01 Origin fixture + `OriginByteLedger`: bytes that reached the socket, per `?runId=` and asset
- [x] P2-02 `OriginDownloader`: ranged reads, resume from cache, hash before commit, retry with backoff
- [x] P2-03 `B0Runner` + `research/configs/b0-origin-only.yaml`: three repeats agree on the asset hash

Origin HTTP copies bytes only: `GET /files/{name}` (200, Range 206, 404) and `GET /manifests/{name}.json`. It never parses or verifies a manifest, and path traversal and absolute paths are rejected. The downloader treats origin as an untrusted source — tampered bytes are retried, then fail closed, and are never stored.

Raw run folders (`research/raw/<runId>/` with `config.yaml`, `git_commit.txt`, `summary.json`) are P10-03, not this phase.

## Phase 3 — Tracker / control plane — done

- [x] P3-01 Announce DTO validation + server-observed IP (an advertised IP is never read)
- [x] P3-02 Redis HASH + per-field TTL 45 s via `HEXPIRE` (one field expires without touching the others)
- [x] P3-03 Candidate ranking v0 (`GET /api/v1/assets/{assetId}/peers?limit=20`; exclude self, site then network group)
- [x] P3-04 `POST /api/v1/auth/peer-token`: short-lived HMAC token bound to `peerId` and site policy
- [x] P3-05 250 peer-record load test: p95 discovery latency and Redis state size recorded, never asserted

Announce now requires `Authorization: Bearer <token>`. A token for another peer, or one claiming a locality it was not issued for, is rejected with 401. Tokens are a dev/research stand-in for mTLS: they gate the control plane and never authorize content.

Still open against blueprint §10: Actuator health / Prometheus, announce rate limiting, `asset:{assetId}:manifest-meta`, and the `capabilities` / `uploadBudget` announce fields that Phase 6 LAPS will need.

Tracker never stores file bytes and is not a trust root.

## Phase 4 — Two-peer Netty data plane — done

- [x] P4-01 `SessionState` + `PeerSession`: HELLO → HELLO_ACK → BITFIELD → ACTIVE, with every state able to fail closed
- [x] P4-02 `StreamingFrameDecoder`: partial, coalesced, and back-to-back frames; bounds checked before any sizing
- [x] P4-03 `ChunkAssembler` + `ChunkStore.putVerifiedFile`: blocks written at their offset, chunk hashed from the staging file
- [x] P4-04 `BlockSender`: BLOCK metadata header then `FileRegion`, with a buffered fallback both covered by tests
- [x] P4-05 `RequestTracker`: CANCEL drops queued work, timeouts re-queue, late data is ignored rather than punished
- [x] P4-06 `ProtocolFuzzTest` + `SeederBoundsTest`: random noise, every single-bit flip, and hostile field values

A leecher accepts a BLOCK only against a request it issued, with matching chunk, offset, and length. Payload goes straight to a per-chunk staging file, so no chunk is ever held in heap, and the chunk becomes real only when its own SHA-256 matches the signed manifest. A mismatch deletes the staging file and ends the session.

The request budget doubles as the memory budget: unrequested bytes are read past and dropped, so a peer can never have more in flight towards us than the budget allows. Disk and hash work runs on a dedicated single thread; the event loop never blocks (blueprint §21.2).

Measured on this machine: `FileRegion` and the buffered path both transfer byte-identical assets on Windows, so the R2 fallback exists but is not currently needed. No throughput numbers are claimed — that is P10 work.

Found and fixed on review of this phase:

- `ChunkInventory` asked the filesystem on every call, so a handshake put one `stat` per chunk on the event loop and a seeder added one more per REQUEST. Presence is now read once, off the loop, and updated as chunks verify.
- Nothing reaped a connection that stalled below ACTIVE. A seeder could be tied up by sockets that said nothing, and a leecher waited forever on a seeder that accepted and went quiet, because the block timeout only starts once blocks are being requested. Both sides now have a handshake deadline.
- The seeder answered PING and accepted HAVE, PONG, and CANCEL before the handshake. An unauthenticated socket should not be useful for anything, so those are refused now.

Not covered in this phase: the token in HELLO is carried but not verified (`PeerAuthPolicy` is the seam for the hardening phase), a hash mismatch ends the session instead of re-fetching from another peer (Phase 6 scheduler), and block-level resume inside a partly received chunk restarts that chunk (chunk-level resume works).

## Phase 5 — Basic swarm (B1) — done

- [x] P5-01 `ChunkAvailability`: BITFIELD on join and HAVE mid-session, both folded into one holder count per chunk
- [x] P5-02 Rarest-first selection with a seeded tie-break, so an even swarm still replays in the same order
- [x] P5-03 `SwarmScheduler`: one shared block queue behind a per-session `BlockSource` view, 8 outstanding per peer
- [x] P5-04 `B1Runner` + `research/configs/b1-basic-swarm.yaml`: eight seeders, repeats agree on the asset hash
- [x] P5-05 Churn smoke at 10 % and 25 %: killed peers' blocks are re-queued and the swarm still finishes

Phase 4 proved one connection; Phase 5 is about the choices that only exist once there are many. Availability is counted, not guessed: a peer contributes its whole bitfield when it joins, single chunks as it announces them, and takes all of it back when it drops. Selection asks for the scarcest wanted chunk first, and ties break on a seed rather than on map order.

No block is handed to two peers at once. A block is leased to one session, and comes back to the queue on timeout, on a hash failure, or when the session dies — none of which invalidates a chunk that already verified. A chunk that fails its hash is rebuilt from nothing rather than patched, because a partly-poisoned staging file is not worth trusting.

Churn is the interesting case and it is the one that is tested: peers are killed mid-transfer, their in-flight work is redistributed, and the run still produces the manifest's asset hash. Which peers die comes from the scenario seed, so a churn run is as replayable as a clean one. A swarm whose survivors no longer cover every chunk fails rather than hangs.

Not covered in this phase: endgame duplicate requests and cancel (P6-04), locality-aware peer choice (Phase 6 — the swarm currently dials candidates in the order the tracker gave them), and per-swarm global byte caps beyond the per-peer budget.

## Not started

- Phases 6–12 as in the blueprint

Peers must call `ManifestVerifier` with a trusted public key. `ManifestJson.parse` only checks JSON shape.
