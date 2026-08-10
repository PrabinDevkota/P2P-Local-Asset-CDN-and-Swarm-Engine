# P2P Local Asset CDN & Swarm Engine

Enterprise-grade peer-to-peer local asset CDN that offloads large file downloads (game updates, OS patches, video) from WAN/cloud origin to LAN peers — cutting egress cost and saturating gigabit local links with Netty zero-copy I/O and SHA-256 chunk integrity.

## Problem

When a large asset ships, every device on the same LAN fetches the same multi-GB file from the cloud. That burns WAN bandwidth, raises egress bills, and slows downloads for everyone.

## Goals

| Objective | Target |
| --- | --- |
| WAN offload | ~80–90% of transfer volume via same-subnet peers |
| Throughput | 800+ Mbps line-rate over LAN (non-blocking zero-copy I/O) |
| Integrity | Mandatory SHA-256 per chunk before disk write or seeding |
| Fairness | Tit-for-tat choke/unchoke so leechers must upload |

## Architecture

Three tiers:

1. **Central Tracker** (Spring Boot + Redis) — peer announce, subnet-aware peer lists; never carries file bytes
2. **Seeder nodes** — hold complete (or enough) chunks
3. **Leecher nodes** — download, verify, and re-seed chunks over the LAN

```
[ Tracker: Spring Boot + Redis ]
        │
        │  POST /announce  ·  GET /peers?infoHash=…
        │  (returns same-LAN IPs + bitfields)
        ▼
┌─────────────┐   Netty binary TCP    ┌─────────────┐
│  Peer A     │◄════════════════════►│  Peer B     │
│  (Seeder)   │   Zero-Copy FileRegion │  (Leecher)  │
└─────────────┘                        └─────────────┘
```

If no reachable LAN peer answers within ~5s, fall back to the central HTTP CDN origin.

## Manifest

Files are split into fixed chunks (e.g. 2 MB or 4 MB). A JSON manifest lists the info-hash and per-chunk SHA-256 digests:

```json
{
  "infoHash": "a8f5f167f44f4964e6c998dee827110c",
  "fileName": "game-patch-v1.4.bin",
  "fileSize": 10737418240,
  "chunkSize": 2097152,
  "totalChunks": 5120,
  "chunkHashes": [
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2"
  ]
}
```

## Tracker API

| Method | Path | Role |
| --- | --- | --- |
| `POST` | `/api/v1/swarm/announce` | Register `{ infoHash, peerId, localIp, port, bitfield }`. Redis hash with **30s TTL** drops stale peers. |
| `GET` | `/api/v1/swarm/peers` | Return active peers for `infoHash`, preferring same `/24` subnet. |

## Wire protocol (Netty TCP)

Length-prefixed frames (no HTTP on the hot path):

```
| Length (4B) | Message ID (1B) | Payload (variable) |
```

| ID | Name | Payload |
| --- | --- | --- |
| `0x01` | HANDSHAKE | InfoHash (32B) + PeerID (16B) |
| `0x02` | BITFIELD | Bitmask of owned chunks |
| `0x03` | HAVE | ChunkIndex (4B) |
| `0x04` | REQUEST | ChunkIndex + Offset + Length |
| `0x05` | PIECE | ChunkIndex + Offset + bytes |
| `0x06` / `0x07` | CHOKE / UNCHOKE | Pause or allow requests |

## Algorithms

- **Rarest-first** — pick chunks with lowest availability across peers so the swarm does not stall on rare pieces.
- **Zero-copy seeding** — `DefaultFileRegion` / `FileChannel.transferTo()` so bytes go kernel page cache → NIC without JVM heap copies or GC spikes.
- **Verify-then-commit** — on a full piece, `SHA256(data)` must match `manifest.chunkHashes[i]`; on mismatch, drop bytes, penalize peer, re-queue chunk.
- **Tit-for-tat** — choke freeloaders; unchoke peers that upload back.

## Planned stack

- **Tracker:** Spring Boot, Redis
- **Peers:** Netty (custom frame codec + seeder/leecher handlers)
- **Integrity:** SHA-256 chunk hashes from the manifest
- **Local test:** Docker Compose (Redis, tracker, seeder, leecher)

## Edge cases

| Failure | Mitigation |
| --- | --- |
| Abrupt disconnect mid-transfer | Netty `exceptionCaught` releases buffers; missing chunks re-queued |
| Chunk poisoning | Hash fail → close channel, blacklist peer ~10 min |
| NAT / no LAN peers | Origin HTTP CDN fallback after ~5s |

## Repo layout

```
/
├── README.md
├── PROJECT_STANDARDS.md
└── tracker/          # Spring Boot + Redis tracker (scaffold)
```

## Status

- Spec + standards docs: done
- `tracker/`: Spring Boot scaffold (Web + Redis) — APIs not implemented yet
- Next: tracker announce/peers APIs, then Netty peer agent, manifest tooling, Compose swarm tests

Industry checklist: [PROJECT_STANDARDS.md](./PROJECT_STANDARDS.md)

## Reference

See `P2P_Asset_CDN_Technical_Specification.docx` (parent folder) for the full architectural blueprint and sample Netty/Docker snippets.
