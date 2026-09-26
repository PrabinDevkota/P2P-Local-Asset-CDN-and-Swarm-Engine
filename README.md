# P2P Local Asset CDN & Swarm Engine

Working name: **SwarmEdge CDN**

Managed peer-assisted CDN for large enterprise assets. Clients verify a **signed release manifest**, discover nearby peers, download **blocks** from untrusted sources, keep only verified **chunks**, and later fall back **peer → site edge → origin**.

This is an engineering system and a research testbed. The first paper focuses on **LAPS** (Locality-Aware Performance Scheduler) and hybrid fallback — not on inventing BitTorrent, QUIC, or FastCDC.

## Status

Phases **0–12 are done** against the blueprint backlog. `./mvnw verify` is the gate. The release checklist is [docs/RELEASE.md](docs/RELEASE.md).

| Phase | State | What it is |
| --- | --- | --- |
| 0 | Done | Maven modules, manifest schema, protocol codecs, ADRs, CI |
| 1 | Done | Split file → SHA-256 chunks → store only matching bytes → SQLite index → Ed25519 sign/verify → rebuild → resume. CLI `gen-key` / `sign` / `verify` |
| 2 | Done | Baseline B0: origin HTTP with byte accounting, peer-side ranged downloader with resume and retries, three-repeat B0 config |
| 3 | Done | Tracker phone book: short-lived peer tokens, announce, ranked candidates, 250-record load test. No file bytes |
| 4 | Done | Two peers over Netty: HELLO → BITFIELD → REQUEST/BLOCK → hash the whole chunk → `putVerified`. Fuzz and bounds suites included |
| 5 | Done | Baseline B1: dial up to 8 peers, rarest-first over counted availability, one shared block queue, HAVE broadcast, churn smoke |
| 6 | Done | Shared locality classes, EWMA goodput/RTT, LAPS scoring and dial order, endgame duplicates, configs for B1/B2/B3 |
| 7 | Done | Cache lookup before network, LRU/quota eviction, warm-start inventory, baseline B4 |
| 8 | Done | EDGE upload budget, deterministic fallback jitter, hybrid cancel-across-sources, baseline B6 |
| 9 | Done | Manifest freshness/rollback, peer quarantine, secret/log audit, B9 security overhead, announce rate limit |
| 10 | Done | Paper scenario factors, netem session, immutable raw runs, `scripts/reproduce_paper.ps1` |
| 11 | Done | FastCDC behind `Chunker`. B4 vs B5 records shared bytes, manifest size, and times. No dedup ratio |
| 12 | Done | Demo, Compose dashboard, paper index, release checklist |

## Demo

Prerequisites: Java 21. Docker is optional and only starts Redis, the tracker, Prometheus, and Grafana.

```powershell
scripts\demo.ps1
```

```sh
./scripts/demo.sh
```

The script builds the tracker jar and runs one origin, one EDGE, and eight seeders in the demo JVM, then repeats the download with the leecher cache still warm. It prints measured peer, edge, origin, and cache bytes. It does not print an offload ratio. With Docker, Grafana is at <http://localhost:3000> (anonymous view is enabled) and Prometheus at <http://localhost:9090>. The metrics port is `9109`.

`./mvnw verify` runs the same cold-then-warm check on the 2 KiB demo asset (`DemoRunTest`). It does not start Docker.

Trust rules that must not drift:

- `ManifestJson.parse` checks JSON **shape** only. Callers must use `ManifestVerifier` with a **trusted public key**, then `ReleaseFreshness.accept`.
- Origin serving a `.json` file is **distribution**, not a trust root.
- The tracker is **not** a trust root and never carries file bytes.

Checklist and phase ticks: [docs/STATUS.md](docs/STATUS.md). File-by-file plan: [docs/PHASE-PLAN.md](docs/PHASE-PLAN.md). Security rules: [docs/SECURITY.md](docs/SECURITY.md).

## First principles

1. **Trust ≠ transfer.** The signed manifest says *what* bytes are valid. Peers only move bytes. The tracker only answers “who nearby?”
2. **Control plane ≠ data plane.** `tracker-service/` (Spring Boot + Redis) is the phone book. `peer-agent/` (Netty) moves blocks over protocol v1.
3. **Chunk ≠ block.** A **chunk** (default 4 MiB) is hashed with SHA-256 and is the only unit stored or seeded. A **block** (default 256 KiB) is a network piece *inside* a chunk. Blocks are not trusted one-by-one; the **whole chunk** must match the manifest hash.
4. **Locality is policy, not `/24`.** Peers carry `siteId` + `networkGroupId`. A network group belongs to one site, so same group ranks above same site, which ranks above a remote site (limit 20). The tracker and the agent share one `Locality` type so they cannot disagree about the same pair of peers.
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
| Data | `peer-agent/` | HELLO → BITFIELD → REQUEST/BLOCK/CANCEL | Phase 5, a leecher against many seeders |
| Storage | `ChunkStore` + `ChunkIndex` | Content-addressed verified chunks, SQLite metadata | Phase 1 (in `manifest-tool/`; peer reuses it) |
| Origin | `origin-fixture/` + `OriginDownloader` | Dumb HTTP byte source, ranged pull with byte accounting | Phase 2 |

```
[ tracker-service: Spring Boot + Redis ]
        │  POST /api/v1/peers/announce
        │  GET  /api/v1/assets/{assetId}/peers?peerId=…&limit=20
        ▼
┌──────────────┐   Netty protocol v1             ┌──────────────┐
│ peer-agent A │◄═══════════════════════════════►│ peer-agent B │
│ (SEEDER/EDGE)│   blocks over TCP               │ (LEECHER)    │
└──────────────┘                                 └──────────────┘
        │                                               │
        └──────── progressive fallback (Phase 8) ─────────┘
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

Redis key: `swarm:{assetId}:peers` (HASH). Field = `peerId`. Per-field TTL **45s**. `AnnounceLoop` heartbeats so a record stays alive. Paper runners still pass candidates in directly so B2/B3 do not depend on a live tracker.

A token for a different peer, or one claiming a locality it was not issued for, is a 401. Tokens gate the control plane; they never authorize content. `HmacPeerAuth` is the same check on HELLO. Set `swarmedge.tracker.token-secret` per deployment — an empty value generates a random secret at startup, so tokens will not survive a restart.

Carried forward from blueprint §10: Actuator health/Prometheus. Announce rate limits, `GET/PUT /api/v1/assets/{assetId}/manifest`, and `capabilities` / `uploadBudget` / `uploadLoad` on announce are in. `uploadLoad` is the 0..1 hint LAPS reads as capacity.

## Peer-to-peer transfer (implemented)

One seeder, one leecher, real sockets. `SeederServer` listens; `LeecherClient` dials and returns a future that completes only when every chunk in the manifest is verified on disk.

```
HELLO ─────────────────────────────────► (asset, peerId, token)
      ◄───────────────────────────────── HELLO_ACK (accepted, maxBlockSize)
BITFIELD ◄────────────────────────────► BITFIELD          → session is ACTIVE
REQUEST (chunk, offset, length) ──────►
      ◄───────────────────────────────── BLOCK header + FileRegion
                                          → bytes go straight to <chunk>.part
                                          → chunk hashed from the file → putVerified
```

What keeps it honest:

- A BLOCK is accepted only against a request this peer issued, with the **same** chunk, offset, and length. Anything else is either late data (read past and dropped) or a protocol violation (connection closed).
- Payload is written to a per-chunk staging file as it arrives, so **no chunk is ever held in heap**. The chunk is hashed from disk and only then moved into the store.
- A hash mismatch deletes the staging file. With one peer that ends the session; in a swarm the chunk is rebuilt and asked of someone else. Nothing unverified survives to be stored or served.
- The outstanding-request budget **is** the memory budget: unrequested bytes never reach disk, so a peer cannot push more at us than the budget allows.
- Hashing and file I/O run on a dedicated thread. The Netty event loop never blocks.
- A slow reader makes the channel unwritable, which is what stops the seeder pulling more blocks off disk.

Both send paths are tested: `FileRegion` (kernel copy, no heap) and a buffered fallback kept for platforms where `transferTo` misbehaves. Two malformed-input suites back this up — `ProtocolFuzzTest` (random noise, every single-bit flip of a valid stream, allocation amplification) and `SeederBoundsTest` (hostile field values at a live listener, which must close the connection and still serve the next honest peer).

Not yet: the token in HELLO is carried but not verified, and a partly received chunk restarts rather than resuming mid-chunk.

## Swarm (implemented)

`SwarmDownloader` is the many-peer version of the same transfer. It dials up to 8 candidates, hands each session a view of **one shared block queue**, and completes when every chunk verifies. A peer that dies is replaced from the spare candidates.

- **Availability is counted, not guessed.** A peer contributes its whole bitfield when it joins, single chunks as it announces them, and takes all of it back when it drops.
- **Rarest first, with a seeded tie-break.** The scarcest wanted chunk goes first. When availability is even, the order comes from the run's seed, so a published run replays exactly instead of following map iteration order.
- **One block, one peer.** A block is leased to a single session and never offered to another. It returns to the queue on timeout, on disconnect, or on a hash failure — none of which invalidates a chunk that already verified.
- **A failed chunk is rebuilt, not patched.** A partly-poisoned staging file is not worth trusting, so the whole chunk goes back to the queue and can be fetched from someone else. This is what Phase 4 could not do.
- **A verified chunk is announced immediately.** HAVE goes to every other connected session, so a leecher becomes useful to the swarm before it has finished.

`SwarmRunner` in `benchmark-runner/` runs this from `research/configs/b1-basic-swarm.yaml`: eight loopback seeders, three repeats, and a churn sweep that kills 10 % and 25 % of them mid-transfer. Every repetition must rebuild a byte-identical asset. Which peers die comes from the seed, so a churn run is as replayable as a clean one. A swarm whose survivors no longer cover every chunk fails rather than hangs.

Not yet: the byte cap is per peer rather than per swarm.

## Wire protocol v1

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

## Scheduling

Two stages, kept separate for experiments:

- **A — which chunk/block?** Rarest-first with a seeded tie-break, plus endgame duplication to at most two sources — **done** (`ChunkAvailability` + `SwarmScheduler`).
- **B — which source?** Locality-only (B2) and **LAPS** (B3) — **done** (`LapsScorer` + `PeerSelector`). Live sessions record observed goodput, RTT, and health into `PeerMetrics`, and the scheduler prefers a better-scoring peer while that peer still has pipeline room.

Source priority is **verified local cache → local/NG peers → same-site EDGE → limited origin**. EDGE is the same `peer-agent` binary with a larger upload budget. Fallback timers are jittered so a flash crowd does not share one origin start time. First verified chunk wins; the other source is cancelled.

## Explicit non-goals (v1)

No public DHT/BitTorrent replacement, no crypto incentives, no AI scheduling, no QUIC/erasure coding/K8s in the first stable release. FastCDC is a **late** research extension after fixed-chunk baselines are stable.

## Stack

| Layer | Baseline |
| --- | --- |
| JDK | **Java 21** (`maven.compiler.release`); CI uses Temurin 21. JDK 25 can compile `--release 21` |
| Tracker | Spring Boot 4.1.x + Redis ≥ 7.4 |
| Peer | Netty **4.2.x** + Jackson + SLF4J |
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
├── peer-agent/          (origin downloader + Netty sessions + swarm coordinator)
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
