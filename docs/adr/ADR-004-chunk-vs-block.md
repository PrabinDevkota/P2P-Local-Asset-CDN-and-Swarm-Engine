# ADR-004: Chunk vs block, fixed chunking first

- Status: Accepted
- Date: 2026-08-14

## Decision

- **Chunk** (default 4 MiB): integrity and cache unit. SHA-256. Only a fully verified chunk is stored and seeded.
- **Block** (default 256 KiB, cap 512 KiB): network request unit inside a chunk. Independently retransmittable; accepted into the cache only after the parent chunk hashes.
- MVP chunking mode is **FIXED**. FastCDC is Phase 11 and must not ship in the scheduler baseline.

Tit-for-tat / CHOKE is **not** in protocol v1. Upload uses administrative budgets and backpressure.

## Consequences

Bitfields are at chunk granularity. REQUEST/BLOCK use chunk index plus block offset/length. Changing these sizes is a configuration/sensitivity study, not a silent protocol break.
