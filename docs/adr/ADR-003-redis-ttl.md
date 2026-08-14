# ADR-003: Redis peer TTL

- Status: Accepted
- Date: 2026-08-14

## Decision

- Key: `swarm:{assetId}:peers` (Redis HASH)
- Field: `peerId`
- Value: compact peer-state JSON (bitfield, port, siteId, networkGroupId, …)
- Per-field TTL: **45 seconds** via HEXPIRE (Redis >= 7.4)
- Heartbeat / re-announce: every **15 seconds**
- Candidate list default limit: **20**
- IP: **server-observed** connection address; advertised IP is not trusted alone

No bulk file data in Redis. Redis is not a trust root.

## Consequences

One peer expiry must not delete other fields in the same hash. Tracker implementation (Phase 3) must use field-level TTL, not a single key expire for the whole swarm.
