# P2P Local Asset CDN & Swarm Engine

Working name: **SwarmEdge CDN**

Managed peer-assisted CDN for large enterprise assets. Clients verify a **signed release manifest**, discover nearby capable peers, download **blocks** from multiple sources, reuse verified cached **chunks**, and fall back progressively **peer → site edge → origin**.

This is both an engineering system and a research testbed. The first paper focuses on **LAPS** (Locality-Aware Performance Scheduler) and hybrid fallback — not on inventing BitTorrent, QUIC, or FastCDC.

## First principles (how the pieces connect)

1. **Trust ≠ transfer.** A signed manifest says *what* bytes are valid. Peers are untrusted byte sources. The tracker never decides content truth and never carries file bytes.
2. **Control plane ≠ data plane.** `tracker/` (Spring Boot + Redis) answers “who might have this asset nearby?” `swarm-node/` (Netty) moves blocks over a binary TCP protocol.
3. **Chunk ≠ block.** A **chunk** (default 4 MiB) is the integrity/cache unit (SHA-256). A **block** (default 256 KiB) is the network request unit inside a chunk.
4. **Locality is policy, not `/24`.** Peers carry `siteId` + `networkGroupId`; the tracker returns a *bounded ranked* candidate list. The peer still picks sources using measured RTT/goodput (LAPS later).
5. **Measure, don’t promise.** Offload % and Mbps are experiment outcomes — not guaranteed SLOs in docs.

## Planes

| Plane | Who | Job |
| --- | --- | --- |
| Trust | Publisher + signed manifest | Authorize the release |
| Control | `tracker/` | Announce, TTL peer state, ranked candidates |
| Data | `swarm-node/` | HELLO → BITFIELD → REQUEST/BLOCK/CANCEL |
| Storage | peer local cache (later) | Content-addressed verified chunks |

```
[ tracker: Spring Boot + Redis ]
        │  POST /api/v1/peers/announce
        │  GET  /api/v1/assets/{assetId}/peers
        ▼
┌──────────────┐   Netty protocol v1    ┌──────────────┐
│ swarm-node A │◄══════════════════════►│ swarm-node B │
│ (SEEDER/EDGE)│   blocks over TCP      │ (LEECHER)    │
└──────────────┘                        └──────────────┘
        │                                      │
        └──────── progressive fallback ────────┘
                    → EDGE → origin HTTP(S)
```

## Manifest (contract direction)

Fixed-chunk MVP first. Manifest is signed (Ed25519); peers verify signature + freshness before any transfer. Illustrative shape:

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

## Tracker API (planned)

| Method | Path | Role |
| --- | --- | --- |
| `POST` | `/api/v1/peers/announce` | Register/refresh peer + bitfield + locality labels |
| `GET` | `/api/v1/assets/{assetId}/peers?limit=20` | Bounded ranked candidates (exclude self) |
| `GET` | `/api/v1/assets/{assetId}/manifest` | Optional manifest metadata index (not trust root) |
| `GET` | `/actuator/health` | Liveness/readiness |

Redis: `swarm:{assetId}:peers` hash, **per-peer field TTL ~45s** (heartbeat ~15s). Observed IP comes from the connection — do not trust a client-advertised IP alone.

## Wire protocol v1 (planned)

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
| `0x06` | BLOCK | metadata + raw bytes (FileRegion-capable send) |
| `0x07` | CANCEL | cancel outstanding requestId |
| `0x08`/`0x09` | PING/PONG | health |
| `0x0A` | ERROR | bounded diagnostic |

No CHOKE/UNCHOKE in the MVP. Upload is limited by **device upload budget / backpressure**, not public-swarm tit-for-tat.

## Scheduling (planned)

Two stages (kept separate for experiments):

- **A — which chunk/block?** Rarest-first baseline (+ endgame urgency later)
- **B — which source?** Locality-only (B2) then **LAPS** performance-weighted score (B3)

Source priority: local verified cache → healthy local peers → same-site EDGE → limited origin.

## Explicit non-goals (v1)

No public DHT/BitTorrent replacement, no crypto incentives, no AI scheduling, no QUIC/erasure coding/K8s in the first stable release. FastCDC is a **late** research extension after fixed-chunk baselines are stable.

## Stack (pinned direction)

| Layer | Baseline |
| --- | --- |
| JDK | **Java 25 LTS** (blueprint also allows 21; pin one LTS and keep both modules identical) |
| Tracker | Spring Boot 4.1.x + Redis ≥ 7.4 |
| Peer | Netty **4.2.x** + Jackson + SLF4J |
| Orchestration | Docker Compose (later) |

## Repo layout (current)

```
/
├── README.md
├── PROJECT_STANDARDS.md
├── tracker/       # control plane scaffold → future tracker-service
└── swarm-node/    # data plane scaffold → future peer-agent
```

Phase 0 will later reshape this into a multi-module Maven monorepo (`common`, `protocol`, `manifest-tool`, …). **Not done yet** — do not invent parallel trees casually.

## Status

- Idea-stage docs corrected to the implementation/research blueprint
- `tracker/`: Spring Boot + Redis scaffold only (no announce/ranking yet)
- `swarm-node/`: Netty/Jackson/SLF4J scaffold only (no protocol codecs yet)
- **Next:** Phase 0 — freeze manifest/protocol contracts + Maven skeleton (no transfer code until contracts exist)

## References

- `P2P_Local_Asset_CDN_Implementation_and_Research_Blueprint.docx` — source of truth for phases, protocol, experiments
- Older idea-stage docs (tech spec / step guide) are superseded where they conflict with the blueprint
