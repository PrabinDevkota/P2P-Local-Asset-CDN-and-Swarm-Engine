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

## Peer session (Phase 4)

`SeederServer` listens for leechers; `LeecherClient` dials one seeder and returns a future that completes only when every chunk verifies. Both sides share `PeerSession`, which holds the handshake state and refuses any frame that does not belong in the state it is in.

| Piece | Job |
| --- | --- |
| `SessionState` / `PeerSession` | HELLO → HELLO_ACK → BITFIELD → ACTIVE; every state can fail closed |
| `StreamingFrameDecoder` (in `protocol/`) | Control frames arrive whole; BLOCK payload is forwarded as it lands |
| `ChunkInventory` | What we hold, and what span a REQUEST is allowed to touch |
| `BlockPlan` | The queue of blocks still wanted, with requeue on timeout or hash failure |
| `RequestTracker` | Outstanding ids, CANCEL, timeouts, and the in-flight byte budget |
| `ChunkAssembler` | Staging file per chunk, hashed from disk, committed only on a match |
| `BlockSender` | BLOCK header then `FileRegion`, or a buffered payload as the fallback |

Rules worth remembering when editing this module:

- Nothing that touches disk or hashes bytes may run on a Netty event loop. Hand it to the disk executor, which must stay single-threaded so block writes and the commit after them stay ordered.
- A BLOCK is only accepted against a request we issued, with matching chunk, offset, and length. Late data is dropped quietly; unsolicited data closes the connection.
- A chunk is advertised only after it verifies. Staging bytes are never visible to `ChunkInventory`.

Not wired yet: `SwarmNode` still has no CLI to point a seeder at a manifest, and the token in HELLO is carried but not verified (`PeerAuthPolicy` is where that lands).
