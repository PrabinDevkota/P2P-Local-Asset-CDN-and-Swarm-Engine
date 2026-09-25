package com.prabin.swarmedge.manifest;

/**
 * How a release was cut. {@code chunkSize} is the fixed size, or the FastCDC average.
 * {@code minSize} and {@code maxSize} are zero for {@code FIXED} so that mode's
 * canonical JSON stays the two fields it has always had.
 */
public record ChunkingSpec(String mode, long chunkSize, long minSize, long maxSize) {

    public ChunkingSpec(String mode, long chunkSize) {
        this(mode, chunkSize, 0, 0);
    }

    public boolean fastCdc() {
        return ManifestValidator.MODE_FASTCDC.equals(mode);
    }
}
