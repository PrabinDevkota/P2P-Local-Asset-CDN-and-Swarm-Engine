package com.prabin.swarmedge.manifest;

import java.util.Arrays;

/**
 * Controlled edits for a cross-version study (blueprint P11-02).
 * The edit is recorded exactly. Similarity is not guessed here.
 */
public final class VersionMutator {

    private VersionMutator() {
    }

    public record Edit(String kind, int at, int length) {
        public Edit {
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("kind is required");
            }
            if (at < 0 || length < 0) {
                throw new IllegalArgumentException("at and length cannot be negative");
            }
        }
    }

    public static byte[] insert(byte[] source, int at, byte[] payload) {
        check(source, at, 0);
        byte[] extra = payload == null ? new byte[0] : payload.clone();
        byte[] out = new byte[source.length + extra.length];
        System.arraycopy(source, 0, out, 0, at);
        System.arraycopy(extra, 0, out, at, extra.length);
        System.arraycopy(source, at, out, at + extra.length, source.length - at);
        return out;
    }

    public static byte[] delete(byte[] source, int at, int length) {
        check(source, at, length);
        byte[] out = new byte[source.length - length];
        System.arraycopy(source, 0, out, 0, at);
        System.arraycopy(source, at + length, out, at, source.length - at - length);
        return out;
    }

    public static byte[] replace(byte[] source, int at, byte[] payload) {
        byte[] extra = payload == null ? new byte[0] : payload;
        check(source, at, extra.length);
        byte[] out = Arrays.copyOf(source, source.length);
        System.arraycopy(extra, 0, out, at, extra.length);
        return out;
    }

    public static Edit edit(String kind, int at, int length) {
        return new Edit(kind, at, length);
    }

    private static void check(byte[] source, int at, int length) {
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        if (at < 0 || length < 0 || at + length > source.length) {
            throw new IllegalArgumentException("edit is outside the file");
        }
    }
}
