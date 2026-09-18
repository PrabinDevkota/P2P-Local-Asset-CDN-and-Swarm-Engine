# Peer agent

Netty data plane (LEECHER / SEEDER / EDGE). Speaks [protocol v1](../docs/protocol-v1.md).

## Run

```powershell
.\mvnw.cmd -pl peer-agent -am exec:java -Dexec.mainClass=com.prabin.swarm_node.SwarmNode
```

## Origin downloader (baseline B0)

`OriginDownloader` pulls an asset from origin over HTTP: one ranged `GET` per chunk, hashed against the signed manifest before it reaches the `ChunkStore`. The caller must have verified the manifest with `ManifestVerifier` first — this class treats origin exactly like an untrusted peer.

- **Resume** is a cache lookup: `ChunkStore.hasVerified` skips a chunk that is on disk and still verified, so a warm cache pulls no origin bytes. An unverified row is fetched again.
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

A connection that never reaches ACTIVE is dropped on a handshake deadline, on both sides. Before ACTIVE, a seeder answers nothing at all — not even PING — because an unauthenticated socket should not be useful for anything.

## Swarm (Phase 5)

`SwarmDownloader` is the many-peer version of `LeecherClient.fetch`: it dials up to `maxPeers` candidates, gives each session a view of one shared block queue, and completes when the asset is whole.

| Piece | Job |
| --- | --- |
| `ChunkAvailability` | How many connected peers hold each chunk, and the rarest-first order over what we still want |
| `SwarmScheduler` | The shared queue; leases a block to one session — two in the endgame — and takes it back on timeout, failure, or death |
| `BlockSource` | What a session sees of the queue — `BlockPlan` for one peer, a scheduler view for a swarm |
| `SessionEvents` | How a `LeecherHandler` reports a remote bitfield, a remote HAVE, or a chunk it just stored |

Choices the swarm makes, and why:

- **Rarest first, seeded ties.** Scarcity is counted from bitfields and HAVEs, never guessed. When every chunk is equally held, the order comes from the run's seed, so a published run replays exactly instead of following map iteration order.
- **One block, one peer.** A leased block is not offered to anyone else. It returns to the queue when a request times out, when the session drops, or when the chunk fails its hash — none of which touches a chunk that already verified.
- **A failed chunk is rebuilt, not patched.** A partly-poisoned staging file is not worth trusting, so the whole chunk goes back to the queue.
- **A HAVE we send is not a favour.** A chunk we verify is announced to every other connected session, which is what makes a leecher useful to the swarm before it has finished.
- **A living peer is not a useful one.** A swarm whose connected peers hold none of the remaining chunks would otherwise sit idle forever: nothing is in flight to time out, and nobody disconnects. A stall deadline ends it, and the failure names the chunks nobody could supply.

## Source choice and the endgame (Phase 6)

Rarest-first decides *which chunk*. LAPS decides *which peer*, and the two stay separate on purpose.

| Piece | Job |
| --- | --- |
| `Locality` / `LocalityClass` (in `common/`) | Same network group, same site, or remote — from labels, never from a `/24` |
| `PeerMetrics` | EWMA goodput and RTT plus a success ratio, written only from what we observed |
| `LapsWeights` | The five §8.2 weights, required to sum to 1 so runs with different weights stay comparable |
| `LapsScorer` | One score per peer and the five terms behind it, so a ranking can be explained |
| `PeerSelector` | Holds the metric history across sessions and turns a flat candidate list into a dial order |

- **Every metric is measured.** Goodput comes from a block that arrived, RTT from a PONG or a block's turnaround, health from attempts that worked against ones that did not. The one exception is declared: how loaded another peer's uplink is cannot be seen from here, so `advertisedUploadLoad` is that peer's own claim — which is why §8.2 gives capacity the smallest weight.
- **A score is relative, not absolute.** Throughput, latency, and capacity are normalized within the candidate set being ranked right now, so the fastest peer in a slow swarm scores 1.0. There is nothing else available to say.
- **An unmeasured peer is neutral, not bad.** Scoring a new peer zero would rank it below one already known to be hopeless, so it would never be asked and never earn the metrics that might clear it.
- **Ties break on a seed, not on peer id.** Ordering by id looks deterministic and is quietly biased: the same peers would win every tie in every run, so a low id would behave like a scheduling advantage and show up in the results as one.
- **Two sources at the tail, never three.** Below `endgameThreshold` blocks a block may go to a second peer and the first arrival cancels the other, so one straggler cannot set the finish time. Duplicating to everybody would turn the tail of every transfer into a broadcast.
- **LAPS grants nothing.** It reorders sources. Bytes from the top-scoring peer are hashed against the signed manifest exactly like everyone else's.

A live session now records into `PeerMetrics`: goodput and RTT from a block that arrived, health from timeouts and refusals. `SwarmDownloader` with a `PeerSelector` prefers a better-scoring session while that session still has room in its pipeline, so B3 can differ from B2 on a single transfer. Without a selector the swarm is B1: first-come among peers that hold the chunk.

Rules worth remembering when editing this module:

- Nothing that touches disk or hashes bytes may run on a Netty event loop. Hand it to the disk executor, which must stay single-threaded so block writes and the commit after them stay ordered.
- A BLOCK is only accepted against a request we issued, with matching chunk, offset, and length. Late data is dropped quietly; unsolicited data closes the connection.
- A chunk is advertised only after it verifies. Staging bytes are never visible to `ChunkInventory`.

## Persistent cache (Phase 7)

`ChunkCache` opens the store together with `cache.db` so a restart sees what was verified. `ChunkInventory` rebuilds its bitfield from `isCached` — the index plus file existence — without re-hashing the warehouse. `CacheEvictor` deletes LRU unreferenced chunks down to a quota and a min-free-space floor; a referenced or just-committed chunk is not deleted to make the numbers look tidy.

Not wired yet: `SwarmNode` still has no CLI to point a seeder at a manifest, and the token in HELLO is carried but not verified (`PeerAuthPolicy` is where that lands).
