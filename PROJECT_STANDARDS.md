# Project Standards Checklist

Aligned with the **Implementation & Research Blueprint** (Aug 2026). Use this as a delivery checklist; the blueprint remains the contract for protocol, manifest, phases, and paper method.

Working name: **SwarmEdge CDN** · Core scheduler research: **LAPS**

---

## 1. Product & Requirements

- [ ] Clear problem statement, users, and **non-goals** (no public DHT, no AI scheduler in v1)
- [ ] Measured outcomes (not promised 80–90% / 800 Mbps marketing targets)
- [ ] Functional: signed manifest, announce, ranked discovery, multi-source blocks, cache reuse, progressive fallback
- [ ] Threat model: poison, forged manifest, spoofed peer, tracker DoS, cache abuse
- [ ] Acceptance criteria per phase gate

---

## 2. Architecture & Design Docs

- [ ] Planes documented: trust / control / data / storage
- [ ] Sequence: verify manifest → announce → discover → HELLO → BITFIELD → REQUEST/BLOCK → verify chunk
- [ ] Data model: assetId, chunk vs block, siteId/networkGroupId, peer roles
- [ ] Protocol v1 + golden byte vectors
- [ ] Manifest v1 JSON Schema + Ed25519 sign/verify
- [ ] Scheduler notes: rarest-first (B1), locality (B2), LAPS (B3); **no tit-for-tat MVP**
- [ ] Progressive fallback peer → EDGE → origin (not fixed 5s all-or-nothing)
- [ ] ADR folder for wire/schema/Redis/scheduler changes

---

## 3. Repository Structure

**Phase 0 layout:**

```
/
├── pom.xml
├── common/
├── protocol/
├── manifest-tool/
├── tracker-service/
├── peer-agent/
├── origin-fixture/
├── benchmark-runner/
├── docs/
├── infra/
└── research/
```

- [x] Single source of truth for protocol/manifest (`docs/` + `protocol/` + `manifest-tool/`)
- [x] Control plane has no Netty file-transfer logic
- [x] Peer does not treat tracker as content trust
- [ ] SemVer releases

---

## 4. Core Product Components

### Tracker (`tracker-service/`)
- [ ] `POST /api/v1/peers/announce` with validation + **observed IP**
- [ ] `GET /api/v1/assets/{assetId}/peers?limit=20` bounded ranking
- [ ] Redis hash + **per-field TTL ~45s** (HEXPIRE / Redis ≥ 7.4)
- [ ] Locality via siteId/networkGroupId (not hard-coded `/24`)
- [ ] Rate limits, peer tokens (dev/research), Actuator health/metrics

### Peer agent (`peer-agent/`)
- [ ] Roles: LEECHER / SEEDER / EDGE (same binary)
- [ ] Protocol v1: HELLO, BITFIELD, HAVE, REQUEST, BLOCK, CANCEL, PING/PONG, ERROR
- [ ] Streaming BLOCK receive; header + FileRegion send where compatible
- [ ] Chunk verify before cache commit/seed; fail closed
- [ ] Upload budget + Netty backpressure (not CHOKE/UNCHOKE MVP)
- [ ] Progressive origin/edge fallback

### Manifest & publishing
- [ ] Fixed chunker + SHA-256 + Ed25519 signed manifest CLI
- [ ] Deterministic assetId from canonical unsigned fields
- [ ] Content-addressed chunk store keyed by chunk hash

### Scheduling & integrity
- [ ] Stage A: rarest-first (+ endgame later)
- [ ] Stage B: locality then LAPS weights
- [ ] SHA-256 full-chunk verify before seed
- [ ] Reputation/quarantine after failures (never replaces crypto checks)

---

## 5. Security

- [ ] Threat model doc
- [ ] Signed manifest + freshness/sequence policy
- [ ] Peer token / auth hooks; production path toward mTLS
- [ ] Strict protocol bounds; fuzz malformed frames
- [ ] Secrets never in git or logs
- [ ] SCA / image scan
- [ ] `SECURITY.md`

---

## 6. Reliability & Resilience

- [ ] Graceful drain; requeue blocks; keep verified chunks
- [ ] Tracker backoff with jitter; sessions continue after discovery
- [ ] Progressive fallback timers + cancel cross-source work
- [ ] Crash/resume from cache index
- [ ] Disk-full / eviction policy

---

## 7. Performance

- [ ] Measured LAN throughput (FileRegion as optimization + buffered fallback)
- [ ] Outstanding request window (default 8/peer)
- [ ] Backpressure / outstanding-byte budgets
- [ ] Benchmark harness with immutable raw runs
- [ ] Resource budgets documented

---

## 8. Observability

- [ ] Structured logs: runId, peerId, assetId, requestId, sourceType, outcome
- [ ] Metrics: peer/edge/origin bytes, hash mismatches, ranking latency, completion time
- [ ] Health endpoints
- [ ] Paper runs under `research/raw/<runId>/` (append-only)

---

## 9. Testing

| Layer | What |
| --- | --- |
| Unit | Manifest, ranking, LAPS, rarest-first, retry math |
| Protocol | Golden vectors, partial/coalesced frames, bounds |
| Integration | Tracker+Redis; two-peer transfer; origin fallback |
| Chaos | Peer kill, Redis restart, churn, netem |
| Security negative | Bad signature, stale sequence, corrupt block, wrong token |
| Paper | B0–B3 configs, repetitions, validation.json |

- [ ] `./mvnw verify` on the pinned LTS JDK (25 here; 21 also blueprint-valid)
- [ ] CI on PRs once modules exist

---

## 10. CI/CD

- [ ] PR: compile + unit + protocol goldens + style
- [ ] Testcontainers Redis/tracker/two-peer when ready
- [ ] Image build; secret/log scan
- [ ] Nightly bench smoke (not every PR if heavy)

---

## 11. Configuration

- [ ] Env/config for ports, TTLs, chunk/block sizes, LAPS weights, fallback timers
- [ ] Documented defaults; no hidden globals
- [ ] Demo / test / paper Compose profiles later

---

## 12. Contracts

- [ ] OpenAPI for tracker
- [ ] Protocol version in HELLO; golden vectors are AGENT-GATE
- [ ] Manifest schema version + canonical serialization
- [ ] ADR required to change wire/schema/Redis/metric names

---

## 13. Packaging & Ops

- [ ] Dockerfiles; Compose demo (≥8 peers + Redis + tracker + origin + EDGE)
- [ ] One-command demo script
- [ ] Runbooks; capacity notes

---

## 14. Documentation

- [ ] README architecture + honest status
- [ ] `docs/protocol-v1.md`, `manifest-v1.md`, threat model, `STATUS.md`
- [ ] Experiment method + reproducibility notes
- [ ] LICENSE, CHANGELOG, SECURITY

---

## 15. Governance

- [ ] Dependency license check
- [ ] No secret commits
- [ ] Peer IP retention/TTL policy
- [ ] Critical-path review for crypto/protocol

---

## 16. Success Metrics (measure only)

| Metric | Why |
| --- | --- |
| Origin offload ratio + peak origin Mbps | Cost / flash-crowd |
| Completion time p50/p95 | Scheduler quality |
| Peer/edge/origin byte share | Where capacity came from |
| Hash/signature failure count | Must stay fail-closed (accepts = 0) |
| Tracker ranking latency / active records | Control-plane scale |
| Cache hit / reuse bytes | Warm-cache (B4+) |

---

## Delivery phases (blueprint order)

0. Architecture freeze (contracts + skeleton)  
1. Local content engine (chunk/sign/store)  
2. Origin baseline B0  
3. Tracker / Redis / ranking  
4. Two-peer Netty protocol  
5. Basic swarm B1 (rarest-first)  
6. Locality + LAPS (B2/B3)  
7. Persistent cache B4  
8. EDGE + progressive fallback  
9. Security hardening  
10. Experiment harness  
11. Optional FastCDC B5  
12. Paper + portfolio release  

**Critical path for a strong resume demo:** phases 0–6.

---

## Definition of done (v1.0)

Matches blueprint §24: clean Java 21 build, 8-peer demo, cold+warm runs, selectable B1/B2/B3, fail-closed security suite, reproducible B0–B3 artifacts, honest README limitations, tagged release.
