package com.prabin.swarmedge.manifest;

public record ChunkEntry(int index, long offset, long length, String sha256) {
}
