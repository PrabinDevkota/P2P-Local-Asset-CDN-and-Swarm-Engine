# Tracker (control plane)

Spring Boot + Redis **control plane** for SwarmEdge CDN.

**Job:** register peers, keep TTL’d swarm state, return a **bounded ranked** candidate list.  
**Not its job:** move asset bytes, decide whether a release is authentic, or pick every block source.

Peers trust the **signed manifest**, not the tracker.

## Stack

- Java 25 LTS (same pin as `swarm-node/`; blueprint also allows 21)
- Spring Boot Web MVC
- Spring Data Redis (peer state; Redis ≥ 7.4 preferred for per-field TTL)

## Run (local)

```powershell
# Redis must be reachable (default localhost:6379)
.\mvnw.cmd spring-boot:run
```

## Planned APIs (blueprint)

| Method | Path | Role |
| --- | --- | --- |
| `POST` | `/api/v1/peers/announce` | Register/refresh peer, bitfield, siteId/networkGroupId |
| `GET` | `/api/v1/assets/{assetId}/peers?limit=20` | Ranked candidates; exclude requester |
| `GET` | `/api/v1/assets/{assetId}/manifest` | Optional metadata index (not trust root) |
| `GET` | `/actuator/health` | Health (when Actuator is added) |

Redis key direction: `swarm:{assetId}:peers` with ~45s per-peer TTL; heartbeat ~15s. Use **server-observed IP**, not a client-claimed address alone.

## Status

Scaffold only. Announce/ranking/auth not implemented. Folder name stays `tracker/` until Phase 0 monorepo rename to `tracker-service/`.

See root [README](../README.md) and [PROJECT_STANDARDS.md](../PROJECT_STANDARDS.md).
