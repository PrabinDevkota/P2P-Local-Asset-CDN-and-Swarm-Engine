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

Not covered: a `>2 GiB` chunker case. Eviction and warm start are Phase 7.

## Phase 2 — Origin baseline (B0) — done

- [x] P2-01 Origin fixture + `OriginByteLedger`: bytes that reached the socket, per `?runId=` and asset
- [x] P2-02 `OriginDownloader`: ranged reads, resume from cache, hash before commit, retry with backoff
- [x] P2-03 `B0Runner` + `research/configs/b0-origin-only.yaml`: three repeats agree on the asset hash

Origin HTTP copies bytes only: `GET /files/{name}` (200, Range 206, 404) and `GET /manifests/{name}.json`. It never parses or verifies a manifest, and path traversal and absolute paths are rejected. The downloader treats origin as an untrusted source — tampered bytes are retried, then fail closed, and are never stored.

Raw run folders (`research/raw/<runId>/` with `config.yaml`, `git_commit.txt`, `summary.json`) are P10-03, not this phase.

## Phase 3 — Tracker / control plane — done

- [x] P3-01 Announce DTO validation + server-observed IP (an advertised IP is never read)
- [x] P3-02 Redis HASH + per-field TTL 45 s via `HEXPIRE` (one field expires without touching the others)
- [x] P3-03 Candidate ranking v0 (`GET /api/v1/assets/{assetId}/peers?limit=20`; exclude self, network group then site)
- [x] P3-04 `POST /api/v1/auth/peer-token`: short-lived HMAC token bound to `peerId` and site policy
- [x] P3-05 250 peer-record load test: p95 discovery latency and Redis state size recorded, never asserted

Announce now requires `Authorization: Bearer <token>`. A token for another peer, or one claiming a locality it was not issued for, is rejected with 401. Tokens are a dev/research stand-in for mTLS: they gate the control plane and never authorize content.

Still open against blueprint §10: Actuator health / Prometheus, `asset:{assetId}:manifest-meta`, and the `capabilities` / `uploadBudget` announce fields. Announce rate limiting is Phase 9.

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

Not covered in this phase: the token in HELLO is carried but not verified (`PeerAuthPolicy` is the seam for the hardening phase), and block-level resume inside a partly received chunk restarts that chunk (chunk-level resume works). A hash mismatch on a single-peer session still ends it; a swarm rebuilds the chunk and asks someone else.

## Phase 5 — Basic swarm (B1) — done

- [x] P5-01 `ChunkAvailability`: BITFIELD on join and HAVE mid-session, both folded into one holder count per chunk
- [x] P5-02 Rarest-first selection with a seeded tie-break, so an even swarm still replays in the same order
- [x] P5-03 `SwarmScheduler`: one shared block queue behind a per-session `BlockSource` view, 8 outstanding per peer
- [x] P5-04 `SwarmRunner` + `research/configs/b1-basic-swarm.yaml`: eight seeders, repeats agree on the asset hash
- [x] P5-05 Churn smoke at 10 % and 25 %: killed peers' blocks are re-queued and the swarm still finishes

Phase 4 proved one connection; Phase 5 is about the choices that only exist once there are many. Availability is counted, not guessed: a peer contributes its whole bitfield when it joins, single chunks as it announces them, and takes all of it back when it drops. Selection asks for the scarcest wanted chunk first, and ties break on a seed rather than on map order.

No block is handed to two peers at once. A block is leased to one session, and comes back to the queue on timeout, on a hash failure, or when the session dies — none of which invalidates a chunk that already verified. A chunk that fails its hash is rebuilt from nothing rather than patched, because a partly-poisoned staging file is not worth trusting.

Churn is the interesting case and it is the one that is tested: peers are killed mid-transfer, their in-flight work is redistributed, and the run still produces the manifest's asset hash. Which peers die comes from the scenario seed, so a churn run is as replayable as a clean one. A swarm whose survivors no longer cover every chunk fails rather than hangs.

Not covered in this phase: per-swarm global byte caps beyond the per-peer budget.

Found and fixed on review of this phase:

- A swarm with living peers that held none of the remaining chunks sat connected and idle forever. Nothing timed out, because nothing was in flight; nobody disconnected; the queue never drained. `ChunkAvailability.reachable` had been written for exactly this case and was never called from anywhere but a test. There is now a stall deadline, and the failure names the chunks no peer could supply instead of reporting a bare timeout.
- The old test for that case asserted the hang — it waited two seconds and treated the timeout as correct behaviour.

## Phase 6 — Locality + LAPS — done

- [x] P6-01 `Locality` / `LocalityClass` in `common/`, shared by the tracker's ranker and the agent's scorer
- [x] P6-02 `PeerMetrics`: EWMA goodput and RTT plus a success ratio, written only from observed data
- [x] P6-03 `LapsScorer` + `LapsWeights` + `PeerSelector`: the §8.2 score, its five terms, and the dial order it produces
- [x] P6-04 Endgame duplicate to at most two sources with a CANCEL for the loser
- [x] P6-05 `research/configs/b1-basic-swarm.yaml`, `b2-locality.yaml`, `b3-laps.yaml`: identical but for the scheduler

Phase 5 answered *which chunk next*. Phase 6 answers *which peer to ask*, and the two are deliberately separate: rarest-first still decides the chunk, and LAPS only decides where to get it.

Locality is two administrative labels, `siteId` and `networkGroupId`, and never an address. A network group belongs to one site, so same-group is the stronger claim and is checked first. This turned up a real bug: the tracker's ranker tested site before group, so a peer across the building tied with one on the same switch and the tie fell through to peer id. The finest distinction available was being discarded on every discovery call.

Every metric is measured. Goodput comes from a block that arrived, RTT from a PONG or a block's turnaround, health from attempts that worked against ones that did not. Nothing a peer asserts about itself is recorded, with one declared exception: `activeUploadLoad` cannot be observed from here, so it is the peer's own claim, and §8.2 gives capacity the smallest weight for that reason.

Two design choices worth stating because they are not obvious. Normalizing within the current candidate set, as §8.2 requires, makes a score relative to a peer's alternatives rather than an absolute rating — the fastest peer in a slow swarm scores 1.0 on throughput, because there is nothing else to say. And a peer with no samples gets a neutral term rather than zero: zero would rank a new peer below one already measured as hopeless, so it would never be asked and never earn the metrics that might clear it.

The endgame exists for the tail, not the average. Near the end there is less work left than there are peers, so idle peers queue behind whichever straggler holds the last block. Below the threshold a block may go to a second peer and the first arrival cancels the other. Two is a hard ceiling: duplicating to everybody turns the tail of every transfer into a broadcast.

LAPS reorders sources and grants nothing. Every byte from the best-scoring peer is still hashed against the signed manifest, and no score exempts anyone.

Not covered in this phase: the agent has no tracker announce loop, so a B2 or B3 run gets its locality labels from the scenario rather than from discovery — the source policy is exercised, discovery is not. The `capabilities` / `uploadBudget` announce fields are still absent from the tracker (§10.1), so `advertisedUploadLoad` has no wire path yet. The window stays fixed at 8 outstanding requests; §8.3's "shrink or expand once the baseline is stable" is not implemented.

Live sessions now record into `PeerMetrics`: a block that arrives updates goodput and RTT, a timeout or refusal updates health. The scheduler prefers a better-scoring connected peer while that peer still has room in its pipeline, so B3 can differ from B2 during a single transfer rather than only on the next dial. An idle better peer (zero leases) is not assumed to be about to ask, or a worse session would wait on a handshake that has not happened yet.

Found and fixed on review of this phase:

- A swarm that assembled a bad chunk blamed whoever delivered the last block and closed that session. In a swarm that block is one slice of a file many peers wrote, so the honest source was the one most likely to be dropped. The chunk is now rebuilt on the shared queue; stale disk writes from the failed round cannot seed the retry; a session is only closed for this if it is the sole source, or if it keeps assembling mismatches.

## Phase 7 — Persistent cache and cross-version reuse — done

- [x] P7-01 Cache lookup before network: `ChunkStore.hasVerified` + origin/swarm skip
- [x] P7-02 `CacheEvictor`: LRU, quota, min-free-space; referenced and just-committed chunks stay
- [x] P7-03 Warm-start inventory from the index and files, without re-hashing; `reconcileIndex` if the database lagged
- [x] P7-04 `research/configs/b4-warm-cache.yaml`: same asset as B0, warm store, origin and cache byte accounting

The store and index already existed. What this phase adds is the policy that uses them. A cache hit is a verified file whose index row is not flagged bad; it bumps LRU and costs no network bytes. An unverified row is fetched again even if the file is still sitting there. Eviction only considers verified, unreferenced chunks, oldest first, and will sit over quota rather than delete a retained release. A restart rebuilds the bitfield from `isCached` — files plus the verified flag — and never walks SHA-256 over the warehouse to do it.

B4 is B0 with `coldCache: false` and a recorded cache ceiling. The first repetition still pays origin; later ones must show origin bytes at zero and cache bytes covering the asset. No offload percentage is asserted.

Not covered in this phase: shrinking the request window, and immutable `research/raw/` folders (P10-03).

## Phase 8 — EDGE role + progressive fallback — done

- [x] P8-01 EDGE is the same `peer-agent` binary: larger upload budget, `BUSY` when the bucket is empty; healthy same-site EDGE ranks first
- [x] P8-02 `FallbackPolicy` with deterministic jitter; `SwarmDownloader.offer` admits EDGE mid-run; stall does not kill a live origin fetch
- [x] P8-03 `OriginChunkFetcher` cancellable Range GET; `HybridDownloader` first-arrival-wins at chunk granularity
- [x] P8-04 `OriginByteLedger` 1 s peak; `research/configs/b6-*.yaml` same asset/seed as B3; `SwarmRunner` records origin / peak / EDGE / peer / cache bytes

EDGE is process policy, not a new protocol role and not a tracker announce field. Origin stays chunk-granular HTTP. B1–B3 YAML and LAPS default weights are unchanged. B6 claims no Mbps or offload %. Live 10/25/50 client counts stay Phase 10.

## Phase 9 — Security hardening — done

- [x] P9-01 Manifest freshness: `ReleaseFreshness` refuses an expired `expiresAt` and a `sequence` behind the per-product high-water mark, with an explicit `StaleReleaseException`. `ManifestVerifier` stays crypto-only.
- [x] P9-02 Peer reputation: `PeerQuarantine` after hash/protocol failures removes a peer from dial eligibility. SHA-256 is never skipped.
- [x] P9-03 Secret/log audit: production log statements must not mention tokens, secrets, or private keys (`SecretLogAuditTest`). [`docs/SECURITY.md`](SECURITY.md) states the rule.
- [x] P9-04 Security overhead: `research/configs/b9-security-overhead.yaml` plus `SecurityOverheadRunner` records hash / sign / verify / HMAC sample times and asserts none of them as an SLO.
- [x] Announce rate limit: `rate:{peerId}:announce`; a burst is 429 and does not block another peer.

Highest-seen sequence is per process (a restart forgets it). HELLO still carries an opaque token; `PeerAuthPolicy` remains the mTLS/HMAC seam. Actuator/Prometheus stay Phase 10.

## Not started

- Phases 10–12 as in the blueprint

Peers must call `ManifestVerifier` with a trusted public key, then `ReleaseFreshness.accept`. `ManifestJson.parse` only checks JSON shape.
