# ADR-004: Chunk vs block, fixed chunking first

- Status: Accepted
- Date: 2026-08-14

## Decision

- **Chunk** (default 4 MiB): integrity and cache unit. SHA-256. Only a fully verified chunk is stored and seeded.
- **Block** (default 256 KiB, cap 512 KiB): network request unit inside a chunk. Independently retransmittable; accepted into the cache only after the parent chunk hashes.
- Scheduler baselines stay **FIXED**. FASTCDC is a second manifest mode (ADR-007) and is not mixed into B1–B3.

Tit-for-tat / CHOKE is **not** in protocol v1. Upload uses administrative budgets and backpressure.

## Consequences

Bitfields are at chunk granularity. REQUEST/BLOCK use chunk index plus block offset/length. Changing these sizes is a configuration/sensitivity study, not a silent protocol break.
