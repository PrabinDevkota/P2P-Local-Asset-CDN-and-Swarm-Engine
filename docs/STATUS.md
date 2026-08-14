# Status

Blueprint source of truth: `P2P_Local_Asset_CDN_Implementation_and_Research_Blueprint.docx`.

## Phase 0 — Architecture freeze

- [x] P0-01 Maven multi-module skeleton + wrapper (`./mvnw verify`)
- [x] P0-02 Manifest v1 JSON Schema + Java records + canonical JSON tests
- [x] P0-03 Protocol v1 + codec golden vectors
- [x] P0-04 Threat model + ADR-001..004
- [x] P0-05 CI (Java 21 Temurin, `./mvnw -B verify`)

## Not started

- Phase 1 local content engine (chunker, sign/verify CLI, chunk store)
- Phase 2 origin baseline
- Phase 3 tracker announce/ranking
- Phase 4 Netty two-peer session
- Phases 5–12 as in the blueprint

Do not begin Netty transfer sessions until this file shows Phase 0 complete and `./mvnw verify` is green.
