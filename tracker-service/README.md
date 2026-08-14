# Tracker service

Spring Boot + Redis **control plane**. Does not carry asset bytes and is not a content trust root.

See [docs/adr/ADR-001-trust-boundary.md](../docs/adr/ADR-001-trust-boundary.md) and [docs/adr/ADR-003-redis-ttl.md](../docs/adr/ADR-003-redis-ttl.md).

## Run

From the repository root (Java 21+):

```powershell
.\mvnw.cmd -pl tracker-service -am spring-boot:run
```

Announce/ranking APIs are Phase 3.
