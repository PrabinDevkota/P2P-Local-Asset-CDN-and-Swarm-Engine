# Industry-Standard Project Checklist

What a production-grade **P2P Local Asset CDN & Swarm Engine** needs before it is considered “complete” by industry standards. No implementation yet — this is the blueprint of deliverables.

---

## 1. Product & Requirements

- [ ] Clear problem statement, target users, and non-goals
- [ ] Measurable SLOs (e.g. WAN offload %, LAN throughput, tracker p99 latency)
- [ ] Functional requirements: announce, peer discovery, transfer, verify, fallback to origin
- [ ] Non-functional requirements: throughput, latency, availability, security, scale
- [ ] Threat model (poisoned chunks, rogue peers, tracker abuse, MITM)
- [ ] Acceptance criteria per feature (definition of done)

---

## 2. Architecture & Design Docs

- [ ] High-level architecture diagram (tracker / seeder / leecher / origin fallback)
- [ ] Sequence diagrams: announce → discover → handshake → transfer → verify
- [ ] Data model: manifest schema, peer registry, bitfield, peer scores
- [ ] Wire protocol specification (message IDs, framing, versioning, compatibility)
- [ ] Chunking strategy (size selection, last-chunk handling, infoHash rules)
- [ ] Algorithm notes: rarest-first, tit-for-tat, choke/unchoke intervals
- [ ] Failure & fallback design (disconnect, poison, NAT, empty swarm)
- [ ] ADR folder (Architecture Decision Records) for major choices (Netty, Redis TTL, etc.)

---

## 3. Repository Structure (industry layout)

Typical monorepo shape (names can vary):

```
/
├── README.md
├── PROJECT_STANDARDS.md
├── docs/                    # architecture, protocol, runbooks
├── tracker/                 # Spring Boot tracker service
├── peer/                    # Netty peer agent (seeder/leecher)
├── common/                  # shared manifest, protocol, crypto helpers
├── tools/                   # manifest generator, load/bench scripts
├── deploy/                  # Docker, Compose, K8s, Helm
├── scripts/                 # local bootstrap, CI helpers
├── .github/workflows/       # CI/CD
├── LICENSE
├── CHANGELOG.md
└── SECURITY.md
```

- [ ] Single source of truth for protocol/manifest (shared module)
- [ ] Clear module boundaries; no circular dependencies
- [ ] Versioned releases (SemVer)

---

## 4. Core Product Components

### Tracker
- [ ] Announce API with peer identity, IP, port, bitfield
- [ ] Peer listing with subnet preference (/24 or configurable CIDR)
- [ ] Redis (or equivalent) presence store with TTL / heartbeat
- [ ] Auth or at least abuse controls (rate limits, announce size caps)
- [ ] Health and readiness endpoints

### Peer agent
- [ ] Dual mode: seeder and leecher (same binary preferred)
- [ ] Custom binary protocol over TCP (Netty)
- [ ] Handshake, bitfield, have, request, piece, choke/unchoke
- [ ] Zero-copy send path for seeding
- [ ] Disk layout for incomplete/complete assets
- [ ] Origin HTTP fallback when LAN swarm fails

### Manifest & publishing
- [ ] Tooling to chunk a file and emit signed/hashed manifest
- [ ] Deterministic infoHash derivation
- [ ] Manifest distribution story (how peers get the JSON)

### Fairness & integrity
- [ ] SHA-256 verify before commit/seed
- [ ] Peer scoring / blacklist on hash failure
- [ ] Tit-for-tat choking to limit free-riding

---

## 5. Security (non-negotiable for “enterprise”)

- [ ] Threat model document
- [ ] TLS for tracker HTTP (and ideally peer transport or authenticated channel)
- [ ] Peer identity that is hard to spoof (stable peerId; ideally keyed)
- [ ] Chunk hash verification always on
- [ ] Tracker input validation and rate limiting
- [ ] Secrets never in git (env / secret manager)
- [ ] Dependency scanning (SCA) and base-image scanning
- [ ] `SECURITY.md` with vulnerability reporting process
- [ ] Optional: manifest signature (publisher key) so peers trust the hash list

---

## 6. Reliability & Resilience

- [ ] Graceful shutdown (finish/flush in-flight pieces, deregister from tracker)
- [ ] Retry/backoff for tracker and peer connections
- [ ] Re-queue incomplete chunks after peer drop
- [ ] Idempotent announce / safe re-registration
- [ ] Tracker HA plan (Redis HA, multiple tracker instances)
- [ ] Clear fallback to origin CDN with timeout budget
- [ ] Disk full / corrupt cache handling

---

## 7. Performance Engineering

- [ ] Throughput targets documented and measured (LAN GbE)
- [ ] Zero-copy path validated under load
- [ ] Connection and piece-pipeline limits (avoid FD / memory blowups)
- [ ] Backpressure on receive buffers
- [ ] Benchmark suite (1 seeder → N leechers; multi-peer swarm)
- [ ] Resource budgets: CPU, RAM, disk IOPS, open sockets

---

## 8. Observability

- [ ] Structured logging (JSON), correlation IDs per transfer/session
- [ ] Metrics: announce rate, active peers, bytes LAN vs origin, hash fails, choke events, p99 piece latency
- [ ] Health/readiness/liveness for tracker and peer
- [ ] Tracing hooks for announce → first-byte → complete
- [ ] Dashboards + alert rules (tracker down, offload % drop, poison spike)

---

## 9. Testing Strategy

| Layer | What |
| --- | --- |
| Unit | Framing codec, rarest-first, hash verify, bitfield ops, scoring |
| Integration | Tracker + Redis; peer handshake; announce→discover |
| Contract | OpenAPI for tracker; protocol fixture vectors for wire messages |
| E2E / system | Docker Compose multi-node swarm (seeder + N leechers) |
| Chaos | Kill peer mid-piece, poison chunk, Redis flap, slow network |
| Performance | Throughput and WAN-bytes-saved regressions in CI (nightly OK) |
| Security | Negative tests for oversized frames, bad hashes, announce floods |

- [ ] Test data fixtures (small deterministic files + known manifests)
- [ ] CI gates: unit + integration must pass on every PR

---

## 10. CI/CD & Quality Gates

- [ ] Build + test on PR
- [ ] Lint / static analysis / format check
- [ ] Dependency vulnerability scan
- [ ] Container image build + scan
- [ ] Artifact publishing (jar/image) with immutable tags
- [ ] Changelog / release notes automation
- [ ] Branch protection: reviews + green CI before merge

---

## 11. Configuration & Environments

- [ ] 12-factor config (env vars / config files; no hardcoding)
- [ ] Separate `local` / `staging` / `prod` profiles
- [ ] Documented knobs: chunk size, ports, tracker URL, TTLs, choke intervals, fallback timeout
- [ ] Sensible defaults for local demo
- [ ] Feature flags if rolling out risky protocol changes

---

## 12. API & Protocol Contracts

- [ ] OpenAPI (or equivalent) for tracker REST
- [ ] Versioned protocol (`protocolVersion` in handshake)
- [ ] Manifest schema version + validation
- [ ] Backward-compatibility policy for wire format changes
- [ ] Error codes / failure semantics documented

---

## 13. Packaging, Deploy & Ops

- [ ] Dockerfile(s) with non-root user, small base image
- [ ] `docker-compose` for local swarm (Redis + tracker + seeder + leechers)
- [ ] Production deploy story (VM / K8s / systemd) even if Compose-first
- [ ] Runbooks: deploy, rollback, scale tracker, rotate secrets, purge bad peer
- [ ] Capacity planning notes (peers per tracker, Redis memory)
- [ ] Backup/restore for any durable state (if manifests live in a DB)

---

## 14. Documentation (ship-with-product)

- [ ] README: what it is, quick start, status
- [ ] Architecture guide
- [ ] Protocol & manifest reference
- [ ] Operator guide (ports, firewall, Redis, TLS)
- [ ] Developer guide (build, test, add message type)
- [ ] SECURITY.md, CONTRIBUTING.md, CODE_OF_CONDUCT (if open source)
- [ ] LICENSE chosen and applied
- [ ] CHANGELOG.md

---

## 15. Compliance, Legal & Governance

- [ ] Open-source license compatibility for Netty/Spring/Redis clients
- [ ] No accidental secret commits (pre-commit / git-secrets / gitleaks)
- [ ] Data handling note: peer IPs in tracker = PII-ish; retention/TTL policy
- [ ] Code owners / review rules for critical paths (crypto, protocol)

---

## 16. Success Metrics (prove it works)

Track these end-to-end; without them the project is demos, not production:

| Metric | Why it matters |
| --- | --- |
| % bytes from LAN vs origin | Core value (cost / WAN offload) |
| Sustained Mbps on LAN | Performance claim |
| Hash failure rate | Integrity / poison detection |
| Time-to-first-byte / time-to-complete | User experience |
| Tracker availability & announce p99 | Control-plane health |
| Swarm size & subnet hit rate | Discovery quality |

---

## Suggested delivery phases (still no code — ordering only)

1. **Foundation** — repo layout, docs skeleton, manifest schema, protocol v1 freeze  
2. **Tracker MVP** — announce + peers + Redis TTL + OpenAPI + health  
3. **Peer MVP** — handshake, request/piece, hash verify, single-seeder download  
4. **Swarm quality** — rarest-first, have, multi-peer, tit-for-tat  
5. **Hardening** — fallback, blacklist, rate limits, TLS, observability  
6. **Production pack** — Compose/K8s, CI, benches, runbooks, release v1.0  

---

## Definition of a “perfect” v1.0

A v1.0 is industry-ready when:

1. Specs and ADRs match running behavior  
2. Multi-node E2E test proves LAN transfer + hash integrity + origin fallback  
3. Security basics (verify-always, abuse limits, secrets hygiene, vulnerability scans) are in place  
4. Metrics prove WAN offload and throughput claims  
5. Ops can deploy, observe, and recover from the failure matrix without tribal knowledge  
6. Releases are versioned, changelogged, and reproducible  

Until those exist, treat the system as a prototype — even if demos look fast.
