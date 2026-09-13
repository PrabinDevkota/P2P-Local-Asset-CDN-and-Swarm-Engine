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
- Reject a BLOCK whose `blockLength` disagrees with `frameLength`: framing must not be a guess.
- Reject a BLOCK whose `requestId` is not outstanding on that connection. A `requestId` that was cancelled, completed, or timed out is *late data* and is read past silently; one that was never issued closes the connection.
- Size nothing from a declared length. `frameLength` is range-checked before use, and a payload buffer is only allocated once its bytes are readable, so announcing a megabyte and going quiet costs the receiver nothing.

## BITFIELD bit order

Chunk `i` is bit `7 - (i % 8)` of byte `i / 8`: the first chunk is the **most significant** bit of the first byte, so a hex dump reads chunks left to right. Padding bits in the final byte **must be zero** — two peers with the same inventory must produce the same bytes, so a set padding bit is a violation rather than something to ignore. A `bitCount` that does not match the manifest's chunk count means the peers disagree about the asset and is also a violation.

## HELLO_ACK reason codes

`reasonCode` is only meaningful when `accepted = 0`.

| Code | Meaning |
| --- | --- |
| `0` | Accepted |
| `1` | Unknown asset: this peer does not serve that `assetId` |
| `2` | Token rejected |
| `3` | Too busy to take another session |

`maxBlockSize` in HELLO_ACK is the responder's ceiling. The requester uses the **smaller** of that and its own, so neither side is told to accept more than it budgeted for.

## Sending a BLOCK

Encode the BLOCK metadata header first, then the payload as a `FileRegion` over the verified chunk file. The file length is checked *before* the header goes out: the header promises exactly `blockLength` bytes, so a short file discovered afterwards would desynchronize every frame after it. A buffered payload path is kept as the portable fallback (blueprint risk R2).

Receivers stream `blockLength` bytes to the chunk assembler as they arrive. No whole chunk is ever buffered.

There is **no CHOKE/UNCHOKE** in v1. Upload is limited by budget and Netty backpressure (ADR-001 / later scheduler).
