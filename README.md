# P2P Local Asset CDN & Swarm Engine

Working name: **SwarmEdge CDN**

Managed peer-assisted CDN for large enterprise assets. Clients verify a **signed release manifest**, discover nearby peers, download **blocks** from untrusted sources, keep only verified **chunks**, and later fall back **peer → site edge → origin**.

This is an engineering system and a research testbed. The first paper focuses on **LAPS** (Locality-Aware Performance Scheduler) and hybrid fallback — not on inventing BitTorrent, QUIC, or FastCDC.

## Status

Phases **0–3 are done** against the blueprint backlog. `./mvnw verify` is the gate and is green. **Phase 4** (two-peer Netty transfer) is next.

| Phase | State | What it is |
| --- | --- | --- |
| 0 | Done | Maven modules, manifest schema, protocol codecs, ADRs, CI |
| 1 | Done | Split file → SHA-256 chunks → store only matching bytes → SQLite index → Ed25519 sign/verify → rebuild → resume. CLI `gen-key` / `sign` / `verify` |
| 2 | Done | Baseline B0: origin HTTP with byte accounting, peer-side ranged downloader with resume and retries, three-repeat B0 config |
| 3 | Done | Tracker phone book: short-lived peer tokens, announce, ranked candidates, 250-record load test. No file bytes |
| 4 | Next | Two peers: HELLO → one 256 KiB block → hash full chunk → `putVerified` → HAVE |
| 5+ | Later | LAPS, multi-peer, EDGE, progressive fallback, experiment harness |

Trust rules that must not drift:

- `ManifestJson.parse` checks JSON **shape** only. Callers must use `ManifestVerifier` with a **trusted public key**.
- Origin serving a `.json` file is **distribution**, not a trust root.
- The tracker is **not** a trust root and never carries file bytes.

Checklist and phase ticks: [docs/STATUS.md](docs/STATUS.md). File-by-file plan: [docs/PHASE-PLAN.md](docs/PHASE-PLAN.md).

## First principles

1. **Trust ≠ transfer.** The signed manifest says *what* bytes are valid. Peers only move bytes. The tracker only answers “who nearby?”
2. **Control plane ≠ data plane.** `tracker-service/` (Spring Boot + Redis) is the phone book. `peer-agent/` (Netty, Phase 4) will move blocks over protocol v1.
3. **Chunk ≠ block.** A **chunk** (default 4 MiB) is hashed with SHA-256 and is the only unit stored or seeded. A **block** (default 256 KiB) is a network piece *inside* a chunk. Blocks are not trusted one-by-one; the **whole chunk** must match the manifest hash.
4. **Locality is policy, not `/24`.** Peers carry `siteId` + `networkGroupId`. The tracker ranks same site first, then same network group, then everyone else (limit 20).
5. **Measure, don’t promise.** Offload % and Mbps are experiment outcomes — not SLOs in this README.

## How a 4 MiB slice is judged good

1. The signed manifest lists each slice: index, offset, length, SHA-256.
2. After the full slice is present, SHA-256 those bytes.
3. Compare to the fingerprint in the manifest.
4. **Match** → `ChunkStore.putVerified` writes `chunks/ab/cd/<64hex>.chunk`.
5. **Mismatch** → fail closed. Bytes are not stored and not seeded.

The warehouse is the shareable cache. Rebuilding the original file (game binary, installer, …) is a separate stitch step for the user.

## Planes

| Plane | Who | Job | Now |
| --- | --- | --- | --- |
| Trust | Publisher + signed manifest | Authorize the release | Phase 1 CLI |
| Control | `tracker-service/` | Peer tokens, announce, 45s TTL, ranked candidates | Phase 3 |
| Data | `peer-agent/` | HELLO → BITFIELD → REQUEST/BLOCK/CANCEL | Codecs exist; session is Phase 4 |
| Storage | `ChunkStore` + `ChunkIndex` | Content-addressed verified chunks, SQLite metadata | Phase 1 (in `manifest-tool/`; peer reuses it) |
| Origin | `origin-fixture/` + `OriginDownloader` | Dumb HTTP byte source, ranged pull with byte accounting | Phase 2 |

```
[ tracker-service: Spring Boot + Redis ]
        │  POST /api/v1/peers/announce
        │  GET  /api/v1/assets/{assetId}/peers?peerId=…&limit=20
        ▼
┌──────────────┐   Netty protocol v1 (Phase 4)   ┌──────────────┐
│ peer-agent A │◄═══════════════════════════════►│ peer-agent B │
│ (SEEDER/EDGE)│   blocks over TCP               │ (LEECHER)    │
└──────────────┘                                 └──────────────┘
        │                                               │
        └──────── progressive fallback (later) ─────────┘
                    → EDGE → origin HTTP
```

## Manifest (implemented)

Fixed-chunk MVP. Ed25519 over canonical **unsigned** JSON. `assetId` = SHA-256 of those same unsigned bytes.

```json
{
  "schemaVersion": 1,
  "productId": "game-x",
  "version": "1.4.0",
  "fileName": "game-x-1.4.0.bin",
  "fileSize": 10737418240,
  "chunking": { "mode": "FIXED", "chunkSize": 4194304 },
  "chunks": [
    { "index": 0, "offset": 0, "length": 4194304, "sha256": "<64 hex chars>" }
  ],
  "createdAt": "2026-08-12T00:00:00Z",
  "expiresAt": "2026-09-12T00:00:00Z",
  "sequence": 17,
  "signingKeyId": "release-key-2026-01",
  "signature": "<base64 Ed25519 over canonical unsigned fields>"
}
```

Contract: [docs/manifest-v1.md](docs/manifest-v1.md). CLI in `manifest-tool/`: `gen-key`, `sign`, `verify`.

## Origin HTTP (implemented)

Test-only untrusted byte source. No Spring. No crypto.

| Method | Path | Role |
| --- | --- | --- |
| `GET` | `/files/{name}` | Whole file (200) or Range (206), streamed from disk |
| `GET` | `/manifests/{name}.json` | Copy JSON bytes only — callers still verify the signature |

Path tricks (`..`, absolute paths) are rejected. Garbage JSON is still served if that file exists on disk.

Add `?runId=…` to attribute bytes to a benchmark run. The server counts only bytes that reached the socket, so an aborted transfer is not billed as delivered, and a 404 or 416 is not billed at all.

## Origin download (implemented)

`OriginDownloader` in `peer-agent/` is baseline B0. One ranged `GET` per 4 MiB chunk, each hashed against the manifest before it is stored. A chunk already in the `ChunkStore` is skipped, so a killed download resumes. Tampered bytes are retried with exponential backoff and then fail closed — they are never written.

`B0Runner` in `benchmark-runner/` repeats that download from `research/configs/b0-origin-only.yaml` and checks every repeat rebuilds a byte-identical asset.

## Tracker API (implemented)

Phone book only. Requires Redis ≥ 7.4 (`HEXPIRE`).

| Method | Path | Role |
| --- | --- | --- |
| `POST` | `/api/v1/auth/peer-token` | Dev/research issuance: short-lived HMAC token bound to `peerId` + site policy |
| `POST` | `/api/v1/peers/announce` | Requires `Authorization: Bearer …`; store bitfield + locality; **observed connection IP** (ignore a client-advertised IP) |
| `GET` | `/api/v1/assets/{assetId}/peers?peerId=…&limit=20` | Exclude self; rank `siteId` then `networkGroupId`; cap at 20 |

Redis key: `swarm:{assetId}:peers` (HASH). Field = `peerId`. Per-field TTL **45s**. Heartbeat / re-announce is ~15s (peer-side, Phase 4).

A token for a different peer, or one claiming a locality it was not issued for, is a 401. Tokens gate the control plane; they never authorize content. Set `swarmedge.tracker.token-secret` per deployment — an empty value generates a random secret at startup, so tokens will not survive a restart.

Carried forward from blueprint §10: Actuator health/Prometheus, announce rate limits, `GET /api/v1/assets/{assetId}/manifest`, and `capabilities` / `uploadBudget` fields for Phase 6 LAPS.

## Wire protocol v1

Codecs and golden vectors exist in `protocol/` (Phase 0). **No live Netty session yet** (Phase 4).

All integers unsigned, big-endian.

```
| frameLength u32 | version u8 | messageType u8 | flags u16 | requestId u64 | payload... |
```

`frameLength` = bytes **after** the 4-byte length field. `requestId = 0` for unsolicited messages (HAVE/PING).

| ID | Name | Role |
| --- | --- | --- |
| `0x01` | HELLO | assetId, peerId, token, capabilities |
| `0x02` | HELLO_ACK | accept / maxBlockSize / reason |
| `0x03` | BITFIELD | chunk availability |
| `0x04` | HAVE | chunkIndex |
| `0x05` | REQUEST | chunkIndex + blockOffset + blockLength |
| `0x06` | BLOCK | metadata + raw bytes |
| `0x07` | CANCEL | cancel outstanding requestId |
| `0x08`/`0x09` | PING/PONG | health |
| `0x0A` | ERROR | bounded diagnostic |

No CHOKE/UNCHOKE in the MVP. Upload uses **device upload budget / backpressure**, not public-swarm tit-for-tat. Contract: [docs/protocol-v1.md](docs/protocol-v1.md).

## Scheduling (later)

Two stages, kept separate for experiments:

- **A — which chunk/block?** Rarest-first baseline (+ endgame urgency later)
- **B — which source?** Locality-only (B2) then **LAPS** (B3)

Source priority (later): local verified cache → healthy local peers → same-site EDGE → limited origin.

## Explicit non-goals (v1)

No public DHT/BitTorrent replacement, no crypto incentives, no AI scheduling, no QUIC/erasure coding/K8s in the first stable release. FastCDC is a **late** research extension after fixed-chunk baselines are stable.

## Stack

| Layer | Baseline |
| --- | --- |
| JDK | **Java 21** (`maven.compiler.release`); CI uses Temurin 21. JDK 25 can compile `--release 21` |
| Tracker | Spring Boot 4.1.x + Redis ≥ 7.4 |
| Peer | Netty **4.2.x** + Jackson + SLF4J (session not wired yet) |
| Origin | JDK `HttpServer` only |
| Orchestration | Docker Compose (later) |

## Build

From the repository root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
.\mvnw.cmd -B verify
```

On Linux/macOS: `./mvnw -B verify`. CI runs the same command on Temurin 21.

Tracker `spring-boot:run` needs a local Redis 7.4+. Origin and `manifest-tool` do not.

## Repo layout

```
/
├── pom.xml
├── common/
├── protocol/
├── manifest-tool/
├── tracker-service/
├── peer-agent/          (origin downloader; Netty session is Phase 4)
├── origin-fixture/
├── benchmark-runner/
├── docs/
├── test-fixtures/
├── infra/
├── research/
└── scripts/
```

Industry checklist: [PROJECT_STANDARDS.md](./PROJECT_STANDARDS.md). Threat model: [docs/security-threat-model.md](docs/security-threat-model.md).

## References

- [`docs/P2P_Local_Asset_CDN_Implementation_and_Research_Blueprint.docx`](docs/P2P_Local_Asset_CDN_Implementation_and_Research_Blueprint.docx) — source of truth for phases, protocol, experiments
- Older idea-stage docs (tech spec / step guide) are superseded where they conflict with the blueprint
