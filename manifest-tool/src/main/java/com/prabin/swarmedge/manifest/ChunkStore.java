package com.prabin.swarmedge.manifest;

import com.prabin.swarmedge.common.id.Hex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Disk cache of verified chunks. Key is SHA-256 hex, not file name or chunk index.
 * Unverified bytes are never written into the chunks/ tree.
 *
 * <p>An optional {@link ChunkIndex} records metadata for eviction and warm start.
 * The files stay the truth; the index is derived and may be rebuilt from disk.
 */
public final class ChunkStore {

    private static final int HASH_BUFFER_BYTES = 64 * 1024;

    private final Path chunksDir;
    private final ChunkIndex index;
    private CacheEvictor evictor;

    public ChunkStore(Path root) throws IOException {
        this(root, null);
    }

    public ChunkStore(Path root, ChunkIndex index) throws IOException {
        Objects.requireNonNull(root, "root");
        this.chunksDir = root.resolve("chunks");
        this.index = index;
        Files.createDirectories(chunksDir);
    }

    /**
     * Run eviction after a successful commit. The evictor holds this store, so it is
     * attached after construction rather than passed in.
     */
    public void attachEvictor(CacheEvictor evictor) {
        this.evictor = Objects.requireNonNull(evictor, "evictor");
    }

    public Path chunksDirectory() {
        return chunksDir;
    }

    public Path pathFor(String sha256Hex) {
        return pathForNormalized(normalizeHash(sha256Hex));
    }

    public boolean contains(String sha256Hex) {
        return Files.isRegularFile(pathFor(sha256Hex));
    }

    /**
     * True when the file is on disk, not flagged unverified, and still the length the
     * index recorded. Does not hash and does not bump LRU: this is what a warm-start
     * bitfield reads. A truncated file is a miss so the next download refetches it.
     */
    public boolean isCached(String sha256Hex) throws IOException {
        String hash = normalizeHash(sha256Hex);
        if (!Files.isRegularFile(pathForNormalized(hash))) {
            return false;
        }
        if (index == null) {
            return true;
        }
        Optional<ChunkIndex.Entry> entry = index.find(hash);
        if (entry.isEmpty()) {
            return true;
        }
        if (!entry.get().verified()) {
            return false;
        }
        return entry.get().length() == Files.size(pathForNormalized(hash));
    }

    /**
     * Cache lookup before network (P7-01). Same as {@link #isCached}, then a touch so
     * LRU eviction sees the hit. A verified local chunk is a hit; an unverified row
     * is not, even if the file is still sitting there.
     */
    public boolean hasVerified(String sha256Hex) throws IOException {
        String hash = normalizeHash(sha256Hex);
        if (!isCached(hash)) {
            return false;
        }
        if (index != null) {
            index.touch(hash);
        }
        return true;
    }

    public Optional<byte[]> read(String sha256Hex) throws IOException {
        String expected = normalizeHash(sha256Hex);
        Path path = pathForNormalized(expected);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        byte[] data = Files.readAllBytes(path);
        if (!expected.equals(Hex.toLowerHex(sha256(data)))) {
            markUnverified(expected);
            throw new IllegalArgumentException("stored chunk is corrupt: " + expected);
        }
        if (index != null) {
            index.touch(expected);
        }
        return Optional.of(data);
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

        Path target = pathForNormalized(expected);
        if (Files.isRegularFile(target)) {
            byte[] existing = Files.readAllBytes(target);
            if (expected.equals(Hex.toLowerHex(sha256(existing)))) {
                record(expected, existing.length, target);
                return target;
            }
            markUnverified(expected);
            Files.delete(target);
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
        record(expected, data.length, target);
        return target;
    }

    /**
     * Commit a staging file that a peer has finished filling. The file is hashed by
     * streaming, so a 4 MiB chunk never lands in heap, and it is moved into place
     * only after the hash matches (blueprint §9.2).
     *
     * <p>The staging file is consumed either way: on a mismatch it is deleted and
     * nothing enters the store, because bytes that failed verification must not
     * survive to be retried or served.
     */
    public Path putVerifiedFile(String expectedSha256Hex, Path staging) throws IOException {
        String expected = normalizeHash(expectedSha256Hex);
        Objects.requireNonNull(staging, "staging");
        try {
            if (!Files.isRegularFile(staging)) {
                throw new IOException("staging file is missing: " + staging);
            }
            String actual = Hex.toLowerHex(sha256OfFile(staging));
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException(
                        "chunk hash mismatch: expected " + expected + " but was " + actual);
            }

            Path target = pathForNormalized(expected);
            if (Files.isRegularFile(target)) {
                if (expected.equals(Hex.toLowerHex(sha256OfFile(target)))) {
                    record(expected, Files.size(target), target);
                    return target;
                }
                markUnverified(expected);
                Files.delete(target);
            }
            Files.createDirectories(target.getParent());
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
            record(expected, Files.size(target), target);
            return target;
        } finally {
            // A successful move already removed it; this only cleans up the failure paths.
            Files.deleteIfExists(staging);
        }
    }

    /** Index only after the bytes are hashed and committed, so staging never looks cached. */
    private void record(String hash, long length, Path target) throws IOException {
        if (index != null) {
            index.recordVerified(hash, length, target);
        }
        if (evictor != null) {
            evictor.evictIfNeeded(hash);
        }
    }

    /**
     * Delete a chunk file and its index row. Referenced chunks are refused: eviction
     * must not drop something a retained release still needs.
     */
    public void remove(String sha256Hex) throws IOException {
        String hash = normalizeHash(sha256Hex);
        if (index != null) {
            Optional<ChunkIndex.Entry> entry = index.find(hash);
            if (entry.isPresent() && entry.get().refCount() > 0) {
                throw new IllegalStateException("chunk is referenced and cannot be evicted: " + hash);
            }
        }
        Files.deleteIfExists(pathForNormalized(hash));
        if (index != null) {
            index.remove(hash);
        }
    }

    /**
     * Make the index match the files without hashing them (P7-03). The files are the
     * truth: a row whose file is gone is dropped, and a file with no row is recorded
     * as verified from its name and length. A restart can then rebuild a bitfield
     * from the index plus {@link #isCached} without walking SHA-256 over every chunk.
     */
    public int reconcileIndex() throws IOException {
        if (index == null) {
            return 0;
        }
        int changed = 0;
        for (String hash : index.hashes()) {
            if (!Files.isRegularFile(pathForNormalized(hash))) {
                index.remove(hash);
                changed++;
            }
        }
        if (!Files.isDirectory(chunksDir)) {
            return changed;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(chunksDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".chunk"))
                    .forEach(files::add);
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            String hash = name.substring(0, name.length() - ".chunk".length());
            try {
                hash = normalizeHash(hash);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (index.find(hash).isEmpty()) {
                index.ensurePresent(hash, Files.size(file), file);
                changed++;
            }
        }
        return changed;
    }

    private void markUnverified(String hash) throws IOException {
        if (index != null) {
            index.markUnverified(hash);
        }
    }

    private Path pathForNormalized(String hash) {
        return chunksDir.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve(hash + ".chunk");
    }

    private static String normalizeHash(String sha256Hex) {
        String hash = sha256Hex.trim().toLowerCase(Locale.ROOT);
        Hex.fromHex(hash);
        if (hash.length() != 64) {
            throw new IllegalArgumentException("sha256 must be 64 hex characters");
        }
        return hash;
    }

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(ChunkStore::newSha256);

    private static byte[] sha256(byte[] data) {
        MessageDigest digest = SHA256.get();
        digest.reset();
        return digest.digest(data);
    }

    /** Hash without holding the file in memory, so chunk size stays a disk concern. */
    private static byte[] sha256OfFile(Path file) throws IOException {
        MessageDigest digest = SHA256.get();
        digest.reset();
        byte[] buffer = new byte[HASH_BUFFER_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
