# Peer agent

Netty data plane (LEECHER / SEEDER / EDGE). Speaks [protocol v1](../docs/protocol-v1.md).

## Run

```powershell
.\mvnw.cmd -pl peer-agent -am exec:java -Dexec.mainClass=com.prabin.swarm_node.SwarmNode
```

Session bootstrap (HELLO state machine, FileRegion send) is Phase 4. Codecs already live in `protocol/`.
