# Security

SwarmEdge treats peers as untrusted byte sources. A signed release manifest authorizes content. The tracker is a phone book, not a trust root.

## Fail closed

Invalid signature, expired release, rollback sequence, wrong chunk hash, unauthorized tracker announce, or a malformed protocol frame is refused. None of those become a cached chunk or a listed peer.

## What is checked

| Gate | Where | What it decides |
| --- | --- | --- |
| Ed25519 | `ManifestVerifier` | The bytes of the canonical unsigned manifest match the stamp |
| Freshness | `ReleaseFreshness` | `expiresAt` is still in the future; `sequence` is not behind the highest seen for that `productId` |
| Chunk hash | `ChunkStore.putVerified` / `ChunkAssembler` | SHA-256 of the whole chunk matches the signed manifest |
| Tracker token | `PeerTokens` | Short-lived HMAC bound to `peerId` and site policy (research stand-in for mTLS) |
| Announce rate | `AnnounceRateLimiter` | One peer cannot flood `POST /api/v1/peers/announce` (`rate:{peerId}:announce`) |
| Reputation | `PeerQuarantine` | Hash or protocol failures make a peer ineligible to be dialled. They never skip a hash |

Crypto and reputation stay separate. A quarantined peer is not asked again during its cooldown. A peer that is asked is still hashed.

## Secrets

Tokens, HMAC secrets, and private keys are not written to logs. `swarmedge.tracker.token-secret` is read from configuration; if it is unset the tracker generates a random secret for this process and warns that tokens will not survive a restart — it does not print the secret.

Do not commit `.env`, PEM private keys, or tracker secrets. Report a suspected leak to the maintainers; rotate the tracker secret and re-issue peer tokens.

## What this phase does not claim

- HELLO is checked when the seeder uses `HmacPeerAuth`. Lab seeders still use `ACCEPT_ANY_TOKEN`. Production path remains mTLS.
- Highest-seen sequence persists only when a `SequenceLedger` file is supplied.
- Security-operation timings (hash / sign / verify / HMAC) are measured by baseline B9 and are not SLOs.
- Highest-seen sequence is per process. A restart forgets it.
