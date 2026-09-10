# Status

Blueprint source of truth: `P2P_Local_Asset_CDN_Implementation_and_Research_Blueprint.docx`.

## Phase 0 — Architecture freeze

- [x] P0-01 Maven multi-module skeleton + wrapper (`./mvnw verify`)
- [x] P0-02 Manifest v1 JSON Schema + Java records + canonical JSON tests
- [x] P0-03 Protocol v1 + codec golden vectors
- [x] P0-04 Threat model + ADR-001..004
- [x] P0-05 CI (Java 21 Temurin, `./mvnw -B verify`)

## Phase 1 — Local content engine

- [x] FileChunker (fixed-size SHA-256 catalog)
- [x] ChunkStore (content-addressed, fail-closed `putVerified`)
- [x] AssetIngestor / AssetMaterializer (file ↔ warehouse round-trip)
- [x] Unsigned `ReleaseManifest` factory
- [x] Ed25519 sign + verify over canonical unsigned JSON
- [x] CLI: `gen-key`, `sign`, `verify`

Not in this phase (later): SQLite chunk index, Netty transfer, tracker APIs.

## Phase 2 — Origin baseline

- [x] P2-01 `OriginHttpServer` (`GET /files/{name}`, path traversal rejected)
- [x] P2-02 Tests: 200 / 404 / traversal
- [x] P2-03 HTTP Range (206) + stream from disk
- [x] P2-04 Serve signed manifest JSON (`GET /manifests/{name}`, copy only)
- [x] P2-05 STATUS complete

Origin HTTP copies bytes only. Callers must still verify a downloaded manifest with a trusted public key.

## Not started

- Phase 3 tracker announce/ranking
- Phase 4 Netty two-peer session
- Phases 5–12 as in the blueprint

Do not begin Netty transfer sessions until Phase 3 can return a candidate list and `./mvnw verify` is green.

Peers must call `ManifestVerifier` with a trusted public key. `ManifestJson.parse` only checks JSON shape.
