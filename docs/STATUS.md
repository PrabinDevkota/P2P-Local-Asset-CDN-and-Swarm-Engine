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

## Not started

- Phase 4 Netty two-peer data plane (P4-01..P4-06)
- Phases 5–12 as in the blueprint

Peers must call `ManifestVerifier` with a trusted public key. `ManifestJson.parse` only checks JSON shape.
