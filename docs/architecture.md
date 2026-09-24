# Architecture

SwarmEdge CDN is four planes. They stay separate so a scheduler experiment cannot quietly become a trust decision.

**Trust.** A signed release manifest names the asset, the chunk size, and each chunk's SHA-256. Peers do not decide what is valid. `ManifestVerifier` checks the signature. `ReleaseFreshness` then refuses an expired or rolled-back release. The tracker is not a trust root.

**Control.** `tracker-service` stores peer records in Redis (`swarm:{assetId}:peers`, 45 s field TTL) and returns at most 20 ranked candidates. Announce needs a bearer token. `GET /actuator/health` and `GET /actuator/prometheus` are operational. Paper numbers come from `research/raw/`, not from that scrape.

**Data.** `peer-agent` speaks protocol v1 over Netty. A chunk is 4 MiB and is hashed from disk. A block is 256 KiB. Rarest-first chooses the chunk. LAPS chooses the peer. EDGE is the same binary with a larger upload budget. Origin is chunk-granular HTTP and is the last rung, not a peer.

**Storage.** Verified chunks live as files. SQLite records length, verified, last access, and references. A cache hit is a verified file. Eviction does not delete a referenced chunk.

Source order is fixed: verified local chunk, then peers, then same-site EDGE, then limited origin. A score can reorder peers. It cannot skip the hash.
