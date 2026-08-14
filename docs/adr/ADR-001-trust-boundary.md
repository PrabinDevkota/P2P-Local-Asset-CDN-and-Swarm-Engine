# ADR-001: Trust boundary

- Status: Accepted
- Date: 2026-08-14

## Decision

Content truth is the **signed release manifest**. The tracker is a bounded discovery/ranking control plane only. Peers never decide what release is valid and never seed unverified bytes.

## Consequences

- Tracker APIs must not be treated as a substitute for signature verification.
- Hash mismatch and unknown signing keys fail closed.
- Changing this boundary requires a new ADR plus negative tests.
