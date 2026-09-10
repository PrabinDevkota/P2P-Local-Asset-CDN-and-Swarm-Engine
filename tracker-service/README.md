# Tracker service

Spring Boot + Redis **control plane**. Does not carry asset bytes and is not a content trust root.

See [docs/adr/ADR-001-trust-boundary.md](../docs/adr/ADR-001-trust-boundary.md) and [docs/adr/ADR-003-redis-ttl.md](../docs/adr/ADR-003-redis-ttl.md).

## Run

From the repository root (Java 21+):

```powershell
.\mvnw.cmd -pl tracker-service -am spring-boot:run
```

Announce/ranking APIs are implemented:

- `POST /api/v1/peers/announce` — observed IP, Redis HASH field TTL 45s
- `GET /api/v1/assets/{assetId}/peers?peerId=...&limit=20` — exclude self, rank `siteId` then `networkGroupId`

Requires Redis ≥ 7.4 for HEXPIRE. This service is not a content trust root.
