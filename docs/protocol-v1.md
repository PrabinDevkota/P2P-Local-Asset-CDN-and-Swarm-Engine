# Protocol v1

Wire contract for the peer data plane. Golden vectors live in `protocol/src/test/resources/vectors/`. Encoder output **must** match those bytes.

## Framing

All integers unsigned, **network byte order (big endian)**.

```
| frameLength u32 | version u8 | messageType u8 | flags u16 | requestId u64 | payload... |
```

- `frameLength` = number of bytes **after** the 4-byte length field.
- Fixed header after length is **12 bytes** (`version` + `type` + `flags` + `requestId`).
- `version` = `1`. Unknown version is a protocol violation (fail closed).
- `requestId` = `0` for unsolicited messages (HAVE, PING, PONG, BITFIELD as inventory). Non-zero for request/response pairs (HELLO, REQUEST/BLOCK, CANCEL, ERROR).
- Maximum `frameLength` = 1 MiB.

## Message types

| ID | Name | Payload |
| --- | --- | --- |
| `0x01` | HELLO | `assetId[32]` + `peerId[16]` + `tokenLength u16` + `token` + `capabilities u32` |
| `0x02` | HELLO_ACK | `accepted u8` + `maxBlockSize u32` + `reasonCode u16` |
| `0x03` | BITFIELD | `bitCount u32` + `byteLength u32` + bitset; `byteLength` = ceil(bitCount/8) |
| `0x04` | HAVE | `chunkIndex u32` |
| `0x05` | REQUEST | `chunkIndex u32` + `blockOffset u32` + `blockLength u32` |
| `0x06` | BLOCK | REQUEST fields + raw bytes of `blockLength` |
| `0x07` | CANCEL | empty payload; `requestId` identifies the outstanding request |
| `0x08` | PING | `nonce u64` |
| `0x09` | PONG | `nonce u64` |
| `0x0A` | ERROR | `code u16` + UTF-8 diagnostic (max 256 bytes); never secrets |

## Bounds (fail closed)

- Reject unknown `messageType`.
- Reject `blockLength` 0 or greater than 512 KiB (absolute cap; negotiated max is typically 256 KiB).
- Reject `blockOffset + blockLength` overflow.
- Reject BITFIELD extra/missing bytes.
- BLOCK send path (Phase 4): encode BLOCK metadata header first, then `FileRegion` for payload. Receivers stream `blockLength` bytes; do not buffer a whole chunk.

There is **no CHOKE/UNCHOKE** in v1. Upload is limited by budget and Netty backpressure (ADR-001 / later scheduler).
