# ADR-005: SQLite chunk index beside the content-addressed store

- Status: Accepted
- Date: 2026-09-12

## Decision

Add `org.xerial:sqlite-jdbc` as the local cache metadata index (blueprint §3 stack table, §9.1 cache layout). It is the first new runtime dependency outside the pinned Spring Boot / Netty BOMs, so this ADR records why.

- Chunk **bytes** stay ordinary files at `<root>/chunks/ab/cd/<64hex>.chunk`. SQLite stores **metadata only**: `chunkHash`, `length`, `verified`, `lastAccess`, `refCount`, `storedAt`.
- A row is written only after `ChunkStore.putVerified` has hashed the bytes and committed the file. There is no path that records an unverified chunk, so a staging file can never appear as cached.
- The index is **derived state**. If `cache.db` is deleted, the store still works and the index can be rebuilt by walking `chunks/`. The files are the truth, not the database.
- The index is optional at construction time: `new ChunkStore(root)` keeps working without a database for the CLI and for tests that do not care about metadata.

## Why not the JDK alone

Phase 7 needs LRU eviction with quota and min-free-space, and Phase 5 needs a warm-start bitfield without re-hashing every file on disk. Both need durable, queryable metadata with atomic updates. A hand-rolled file format would reimplement transactions badly; `java.util.prefs` and flat JSON do not survive concurrent writers or partial writes.

## Consequences

- `sqlite-jdbc` ships a native library per platform. It is a supporting dependency of `manifest-tool` and later `peer-agent`; it must never appear on the tracker's classpath, because the control plane holds no content state.
- No blocking SQLite call may run on a Netty event loop (blueprint §21.2). Phase 4 onward must use the disk executor.
- Schema changes are migrations and need a new ADR entry.
