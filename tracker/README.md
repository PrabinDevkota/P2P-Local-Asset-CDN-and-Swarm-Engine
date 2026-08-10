# Tracker service

Spring Boot + Redis control plane for the P2P Local Asset CDN.

Registers peers (`announce`), returns subnet-aware peer lists, and never carries file bytes.

## Stack

- Java 25
- Spring Boot Web MVC
- Spring Data Redis

## Run (local)

```bash
# Redis must be reachable (default localhost:6379)
./mvnw spring-boot:run
```

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

## Planned APIs

| Method | Path | Role |
| --- | --- | --- |
| `POST` | `/api/v1/swarm/announce` | Register peer presence + bitfield |
| `GET` | `/api/v1/swarm/peers` | List active peers for an infoHash |

See the root [README](../README.md) and [PROJECT_STANDARDS.md](../PROJECT_STANDARDS.md) for the full system plan.
