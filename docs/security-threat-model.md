# Security threat model (Phase 0)

Fail closed: invalid signature, stale release, wrong hash, wrong asset ID, unauthorized peer, or malformed frames are **never** silently accepted.

## Assets to protect

- Release authenticity and freshness
- Chunk integrity
- Peer / control-plane identity
- Tracker, EDGE, and origin availability
- Site identifiers and other operational metadata
- Integrity of benchmark artifacts (later)

## Threats and required controls

| Threat | Control | Phase |
| --- | --- | --- |
| Malicious peer sends bad bytes | SHA-256 full-chunk verify before cache commit/seed | 1 / 4 |
| Manifest + file replaced together | Ed25519 over canonical unsigned manifest; trusted keys local | 1 / 9 |
| Rollback / stale release | `sequence` + `expiresAt` + highest-seen sequence policy | 9 (done) |
| Peer ID spoofing | Short-lived token bound to peerId/site; mTLS hook in production | 3 / 9 |
| Frame / offset abuse | Strict protocol bounds, max lengths, requestId tracking | 0 (codec) / 4 |
| Announce DoS | Rate limit, bounded payload, TTL, candidate limit | 3 / 9 |
| Cache poisoning | Only verified hash-addressed chunks are seedable | 1 / 7 |
| Secret leakage in logs | Tokens and keys never logged | 9 (done) |

## Trust boundary

See [ADR-001](adr/ADR-001-trust-boundary.md). The tracker is **not** a trust root. Peers are untrusted byte sources. The signed manifest authorizes content.
