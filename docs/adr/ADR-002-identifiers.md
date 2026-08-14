# ADR-002: Identifiers

- Status: Accepted
- Date: 2026-08-14

## Decision

| ID | Width / type | Meaning |
| --- | --- | --- |
| `assetId` | 32 bytes | SHA-256 of canonical **unsigned** manifest JSON |
| `peerId` | 16 bytes | Peer instance identity; later bound to an auth token |
| Chunk hash | 64 lowercase hex SHA-256 | Content-addressed cache key |
| `chunkIndex` | u32 | Position in **this** manifest’s chunk list (bitfield / HAVE / REQUEST) |

Chunk hash is for storage/reuse. `chunkIndex` is for the wire bitfield of one release.

## Consequences

Cross-version reuse keys on chunk hash, not index. Protocol messages use index because bitfields are per-asset.
