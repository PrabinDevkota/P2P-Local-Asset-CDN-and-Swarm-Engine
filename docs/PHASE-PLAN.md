# Next-phase plan (file-by-file)

Phase 0 contracts and Phase 1 local content engine are done. `./mvnw verify` is the gate. Do not start Netty transfer (Phase 4) until Phase 2 origin can serve a file and Phase 3 tracker can return a candidate list.

## Done

| Phase | What exists |
| --- | --- |
| 0 | Maven modules, manifest schema, protocol codecs, ADRs, CI |
| 1 | Chunk → store → signed manifest → verify → rebuild; CLI `gen-key` / `sign` / `verify` |

## Phase 2 — Origin baseline (`origin-fixture/`)

Fake company server. Peers later fall back here. No P2P yet.

| Step | File | Job |
| --- | --- | --- |
| P2-01 | `OriginHttpServer.java` | JDK HTTP server; `GET /files/{name}` from a root dir; reject `..` paths |
| P2-02 | `OriginHttpServerTest.java` | 200 whole file, 404 missing, path-traversal rejected |
| P2-03 | range support in the same server | `Range: bytes=start-end` → 206 (needed for later block fallback) |
| P2-04 | `GET /manifests/{name}` | Serve a signed JSON already produced by `manifest-tool` |
| P2-05 | STATUS/README | Mark Phase 2 complete |

Keep this module free of Spring. Origin is a dumb byte source; trust still comes from the signed manifest.
Serving a `.json` file later is **distribution only** — callers must still run `ManifestVerifier`.
P2-03 must **stream** from disk (no `readAllBytes`) so a 10 GB asset cannot blow RAM.

## Phase 3 — Tracker announce / ranking (`tracker-service/`)

Phone book only. No file bytes in Redis.

| Step | Job |
| --- | --- |
| P3-01 | Announce DTO + validation (`assetId`, `peerId`, port, `siteId`, `networkGroupId`, bitfield) |
| P3-02 | `POST /api/v1/peers/announce` — observed IP, per-peer Redis field TTL 45s |
| P3-03 | `GET /api/v1/assets/{assetId}/peers?limit=20` — exclude self, rank site then network group |
| P3-04 | Tests with embedded/test Redis; fail closed on bad payloads |

## Phase 4 — Two-peer Netty session (`peer-agent/`)

Use existing `protocol/` codecs. Still no LAPS.

| Step | Job |
| --- | --- |
| P4-01 | Netty server/client bootstrap + HELLO / HELLO_ACK |
| P4-02 | BITFIELD / HAVE from local `ChunkStore` |
| P4-03 | REQUEST / BLOCK / CANCEL for one 256 KiB block |
| P4-04 | Assemble blocks into a chunk; `putVerified` then HAVE |
| P4-05 | Two-process test: seeder + leecher, one small file |

## Later (do not pull forward)

- Phase 5+ scheduler / LAPS, multi-peer, EDGE role
- SQLite chunk index
- Origin fallback policy (peer → edge → origin)
- Benchmarks (no invented Mbps / offload %)
- mTLS, sequence rollback (threat model Phase 9)

## Working rule

One production file (or one test file) per commit. Tests must pass before the next file.
