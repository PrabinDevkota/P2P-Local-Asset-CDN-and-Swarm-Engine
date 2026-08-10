# Swarm node (peer)

Plain Maven + **Netty** peer agent (seeder / leecher). Not Spring Boot — that stays in `tracker/`.

## Dependencies

- Netty — binary TCP framing and zero-copy transfers
- Jackson — `manifest.json` parsing
- SLF4J + Logback — logging

## Run

```powershell
.\mvnw.cmd compile exec:java -Dexec.mainClass=com.prabin.swarm_node.SwarmNode
```

Or package a fat jar:

```powershell
.\mvnw.cmd package
java -jar target\swarm-node-0.0.1-SNAPSHOT.jar
```

Env knobs (used later by the full peer):

| Variable | Default | Meaning |
| --- | --- | --- |
| `NETTY_PORT` | `9091` | Peer listen port |
| `TRACKER_URL` | `http://localhost:8080` | Tracker base URL |
| `HAS_FILE` | `false` | Seeder vs leecher |

## Status

Scaffold only. Next phases: frame decoder, handshake/bitfield, rarest-first, zero-copy `DefaultFileRegion`, piece verify.
