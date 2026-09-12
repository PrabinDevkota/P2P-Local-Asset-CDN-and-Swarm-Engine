# Peer agent

Netty data plane (LEECHER / SEEDER / EDGE). Speaks [protocol v1](../docs/protocol-v1.md).

## Run

```powershell
.\mvnw.cmd -pl peer-agent -am exec:java -Dexec.mainClass=com.prabin.swarm_node.SwarmNode
```

## Origin downloader (baseline B0)

`OriginDownloader` pulls an asset from origin over HTTP: one ranged `GET` per chunk, hashed against the signed manifest before it reaches the `ChunkStore`. The caller must have verified the manifest with `ManifestVerifier` first — this class treats origin exactly like an untrusted peer.

- **Resume** is a side effect of content addressing: a chunk already in the store is skipped.
- **Retry** is exponential with a cap. Bad bytes and transport errors are retried the same way, then fail closed.
- **Accounting**: pass a `runId` in the settings and the origin fixture bills those bytes to that run.

Session bootstrap (HELLO state machine, FileRegion send) is Phase 4. Codecs already live in `protocol/`.
