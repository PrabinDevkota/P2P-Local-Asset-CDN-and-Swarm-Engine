# Tracker service

Spring Boot + Redis **control plane**. Does not carry asset bytes and is not a content trust root.

See [docs/adr/ADR-001-trust-boundary.md](../docs/adr/ADR-001-trust-boundary.md) and [docs/adr/ADR-003-redis-ttl.md](../docs/adr/ADR-003-redis-ttl.md).

## Run

From the repository root (Java 21+):

```powershell
.\mvnw.cmd -pl tracker-service -am spring-boot:run
```

Phase 3 APIs are implemented:

- `POST /api/v1/auth/peer-token` — short-lived HMAC token bound to `peerId`, `siteId`, `networkGroupId`
- `POST /api/v1/peers/announce` — requires `Authorization: Bearer …`; observed IP, Redis HASH field TTL 45s
- `GET /api/v1/assets/{assetId}/peers?peerId=...&limit=20` — exclude self, rank `siteId` then `networkGroupId`

Requires Redis ≥ 7.4 for HEXPIRE. This service is not a content trust root.

## Peer tokens

A token proves which peer is announcing and which site policy it may claim. It authorizes nothing about content: a valid token cannot make a bad chunk acceptable, and an expired one cannot invalidate a chunk that already hashed correctly. Treat it as a dev/research stand-in for mTLS.

| Setting | Default | Meaning |
| --- | --- | --- |
| `swarmedge.tracker.token-secret` | empty | HMAC secret, at least 32 bytes. Empty generates a random one per boot, so tokens die on restart and do not work across instances |
| `swarmedge.tracker.token-ttl-seconds` | `300` | Token lifetime |

Announce returns 401 when the token is missing, expired, re-signed, issued for another `peerId`, or carries a different locality than the announce body.
