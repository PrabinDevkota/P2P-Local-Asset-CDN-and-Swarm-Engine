# Manifest v1

Executable contract: `manifest-tool/src/main/resources/schema/manifest-v1.json` plus `ManifestValidator` / `CanonicalManifest`.

## Purpose

A release manifest is the **only** content-trust document. Peers and the tracker do not decide what bytes are authentic. Ed25519 sign/verify of this document is Phase 1; Phase 0 freezes the schema and canonical bytes.

## Fields

| Field | Rule |
| --- | --- |
| `schemaVersion` | `1` |
| `productId`, `version`, `fileName` | non-empty strings |
| `fileSize` | unsigned 64-bit in the Java model (`long`, non-negative) |
| `chunking.mode` | `FIXED` or `FASTCDC` |
| `chunking.chunkSize` | fixed size, or the FastCDC average |
| `chunking.minSize`, `chunking.maxSize` | FastCDC only; omitted from FIXED canonical JSON |
| `chunks[]` | `index`, `offset`, `length`, `sha256` (64 lowercase hex) |
| `createdAt`, `expiresAt` | UTC `YYYY-MM-DDTHH:MM:SSZ` |
| `sequence` | non-negative; used for rollback policy later |
| `signingKeyId` | key identifier |
| `signature` | present; cryptographic verify is Phase 1 |

## Invariants

- Chunks are ordered by `index` 0..n-1, contiguous, no overlap, cover `fileSize` exactly.
- FIXED: every non-final chunk equals `chunkSize`. The final chunk may be shorter.
- FASTCDC: a non-final chunk is between `minSize` and `maxSize`. The final chunk may be shorter than `minSize`. `chunkSize` is the average.
- `assetId` = SHA-256 of **canonical unsigned JSON** (all fields except `signature`, compact, fixed key order). See `CanonicalManifest`.
- Signature (later) is over the same unsigned canonical bytes.

## Defaults (MVP)

- Integrity chunk: 4 MiB
- Network block (not in the manifest): 256 KiB — see [protocol-v1.md](protocol-v1.md) and [adr/ADR-004-chunk-vs-block.md](adr/ADR-004-chunk-vs-block.md)
