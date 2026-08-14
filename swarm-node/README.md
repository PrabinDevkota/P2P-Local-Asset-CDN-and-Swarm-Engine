# Swarm node (peer data plane)

Plain Maven + **Netty** peer agent (LEECHER / SEEDER / EDGE). Not Spring Boot — that stays in `tracker/`.

**Job:** verify signed manifests, exchange protocol v1 messages, transfer **blocks**, verify **chunks**, seed verified data, fall back to edge/origin when needed.  
**Not its job:** be the source of release trust, or dump the whole swarm from Redis.

## Dependencies

- Netty 4.2.x — framed TCP + future FileRegion send path
- Jackson — manifest/API JSON
- SLF4J + Logback — structured logging later

## Run

```powershell
.\mvnw.cmd compile exec:java
```

Or package a fat jar:

```powershell
.\mvnw.cmd package
java -jar target\swarm-node-0.0.1-SNAPSHOT.jar
```

## Env knobs (scaffold)

| Variable | Default | Meaning |
| --- | --- | --- |
| `NETTY_PORT` | `9091` | Peer listen port |
| `TRACKER_URL` | `http://localhost:8080` | Control-plane base URL |
| `PEER_ROLE` | `LEECHER` | `LEECHER` / `SEEDER` / `EDGE` |
| `SITE_ID` | `site-local` | Administrative site label (locality) |
| `NETWORK_GROUP_ID` | `ng-local` | Finer locality group (replaces hard-coded `/24`) |

## Status

Scaffold only. No codecs/scheduler/cache yet. Folder stays `swarm-node/` until Phase 0 rename to `peer-agent/`.

Protocol direction: HELLO → HELLO_ACK → BITFIELD → REQUEST/BLOCK/CANCEL (not old HANDSHAKE/PIECE/CHOKE).
