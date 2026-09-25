# ADR-007: FastCDC as a second chunking strategy

- Status: Accepted
- Date: 2026-09-25

## Decision

- `Chunker` is the publisher seam. `FileChunker` and `FastCdcChunker` both return an ordered `List<ChunkEntry>`.
- FIXED canonical JSON stays `mode` and `chunkSize`. FASTCDC adds `minSize` and `maxSize`. Existing signed fixtures and asset IDs do not change.
- The gear table is derived from a fixed seed in this tree. Cuts are reproducible here. They are not claimed to match a published FastCDC table.
- `BlockPlan` still slices each chunk by `chunk.length()`. Variable lengths do not add a protocol message.
- B5 records shared hash bytes, canonical manifest size, chunking time, elapsed time, and a 95% bootstrap interval. It does not rank B4 against B5 and does not state a dedup ratio.
- B1–B3 YAML numbers and LAPS weights stay as they are. CDC is not part of the scheduler baseline.

## Consequences

A new manifest can name a chunk hash already in the content-addressed store. That is reuse. It is not a transfer of trust: the peer still verifies the manifest signature and each chunk hash.
