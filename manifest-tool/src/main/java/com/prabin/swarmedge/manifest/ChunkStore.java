package com.prabin.swarmedge.manifest;

import com.prabin.swarmedge.common.id.Hex;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Disk cache of verified chunks. Key is SHA-256 hex, not file name or chunk index.
 * Unverified bytes are never written into the chunks/ tree.
 */
public final class ChunkStore {

    private final Path chunksDir;

    public ChunkStore(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        this.chunksDir = root.resolve("chunks");
        Files.createDirectories(chunksDir);
    }

    public Path pathFor(String sha256Hex) {
        String hash = normalizeHash(sha256Hex);
        return chunksDir.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve(hash + ".chunk");
    }

    public boolean contains(String sha256Hex) {
        return Files.isRegularFile(pathFor(sha256Hex));
    }

    public Optional<byte[]> read(String sha256Hex) throws IOException {
        Path path = pathFor(sha256Hex);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(Files.readAllBytes(path));
    }

    /**
     * Hash the bytes; store only if they match {@code expectedSha256Hex}.
     * Write goes to a temp file, then rename into place so readers never see a partial chunk.
     */
    public Path putVerified(String expectedSha256Hex, byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        String expected = normalizeHash(expectedSha256Hex);
        String actual = Hex.toLowerHex(sha256(data));
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("chunk hash mismatch: expected " + expected + " but was " + actual);
        }

        Path target = pathFor(expected);
        if (Files.isRegularFile(target)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), expected, ".tmp");
        try {
            Files.write(temp, data);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        return target;
    }

    private static String normalizeHash(String sha256Hex) {
        Hex.fromHex(sha256Hex);
        String hash = sha256Hex.trim().toLowerCase(Locale.ROOT);
        if (hash.length() != 64) {
            throw new IllegalArgumentException("sha256 must be 64 hex characters");
        }
        return hash;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
