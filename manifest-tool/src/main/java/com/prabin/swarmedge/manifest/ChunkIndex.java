package com.prabin.swarmedge.manifest;

import com.prabin.swarmedge.common.id.Hex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Metadata for the content-addressed chunk store. Bytes stay in ordinary files;
 * this holds only what eviction and warm start need. A row appears only after
 * {@link ChunkStore#putVerified} has hashed and committed the bytes, so staging
 * data can never look cached. See docs/adr/ADR-005-chunk-index.md.
 */
public final class ChunkIndex implements AutoCloseable {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS chunk (
                chunk_hash  TEXT PRIMARY KEY,
                length      INTEGER NOT NULL,
                verified    INTEGER NOT NULL,
                last_access INTEGER NOT NULL,
                ref_count   INTEGER NOT NULL,
                stored_at   TEXT NOT NULL
            )""";

    private final Connection connection;
    private final Clock clock;

    public static ChunkIndex open(Path databaseFile) throws IOException {
        return open(databaseFile, Clock.systemUTC());
    }

    public static ChunkIndex open(Path databaseFile, Clock clock) throws IOException {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Objects.requireNonNull(clock, "clock");
        Path absolute = databaseFile.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute(SCHEMA);
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
            return new ChunkIndex(connection, clock);
        } catch (SQLException e) {
            throw new IOException("cannot open chunk index at " + absolute, e);
        }
    }

    private ChunkIndex(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /** Upsert a chunk that is already hashed and committed. Keeps any existing refCount. */
    public synchronized void recordVerified(String sha256Hex, long length, Path storedAt) throws IOException {
        String hash = requireHash(sha256Hex);
        Objects.requireNonNull(storedAt, "storedAt");
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
        String sql = """
                INSERT INTO chunk (chunk_hash, length, verified, last_access, ref_count, stored_at)
                VALUES (?, ?, 1, ?, 0, ?)
                ON CONFLICT(chunk_hash) DO UPDATE SET
                    length = excluded.length,
                    verified = 1,
                    last_access = excluded.last_access,
                    stored_at = excluded.stored_at""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hash);
            statement.setLong(2, length);
            statement.setLong(3, now());
            statement.setString(4, storedAt.toAbsolutePath().normalize().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("cannot record chunk " + hash, e);
        }
    }

    public synchronized Optional<Entry> find(String sha256Hex) throws IOException {
        String hash = requireHash(sha256Hex);
        String sql = "SELECT chunk_hash, length, verified, last_access, ref_count, stored_at"
                + " FROM chunk WHERE chunk_hash = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hash);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(toEntry(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IOException("cannot read chunk " + hash, e);
        }
    }

    /**
     * Record a file found on disk during reconcile. Inserts a new row, but does not
     * bump {@code last_access} on a row that already exists — a restart must not
     * look like a cache hit, or LRU eviction would forget what was actually used.
     */
    public synchronized void ensurePresent(String sha256Hex, long length, Path storedAt) throws IOException {
        String hash = requireHash(sha256Hex);
        Objects.requireNonNull(storedAt, "storedAt");
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
        String sql = """
                INSERT INTO chunk (chunk_hash, length, verified, last_access, ref_count, stored_at)
                VALUES (?, ?, 1, ?, 0, ?)
                ON CONFLICT(chunk_hash) DO UPDATE SET
                    length = excluded.length,
                    verified = 1,
                    stored_at = excluded.stored_at""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hash);
            statement.setLong(2, length);
            statement.setLong(3, now());
            statement.setString(4, storedAt.toAbsolutePath().normalize().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("cannot ensure chunk " + hash, e);
        }
    }

    /** Called when a chunk is reused or served. Eviction reads this. */
    public synchronized void touch(String sha256Hex) throws IOException {
        update("UPDATE chunk SET last_access = ? WHERE chunk_hash = ?", sha256Hex, now());
    }

    /** One more locally retained release references this chunk. */
    public synchronized void retain(String sha256Hex) throws IOException {
        update("UPDATE chunk SET ref_count = ref_count + 1, last_access = ? WHERE chunk_hash = ?",
                sha256Hex, now());
    }

    /** One fewer release references this chunk. Never drops below zero. */
    public synchronized void release(String sha256Hex) throws IOException {
        update("UPDATE chunk SET ref_count = MAX(ref_count - 1, 0), last_access = ? WHERE chunk_hash = ?",
                sha256Hex, now());
    }

    /**
     * Flag a row whose file failed a re-hash after a crash. The chunk stops counting
     * as cached until it is stored again, so it can never be advertised or seeded.
     */
    public synchronized void markUnverified(String sha256Hex) throws IOException {
        update("UPDATE chunk SET verified = 0, last_access = ? WHERE chunk_hash = ?", sha256Hex, now());
    }

    /** Every hash we have a row for, including unverified ones, so reconcile can drop ghosts. */
    public synchronized List<String> hashes() throws IOException {
        return listHashes("SELECT chunk_hash FROM chunk ORDER BY chunk_hash");
    }

    /** Hashes a warm start may advertise, oldest access first. */
    public synchronized List<String> verifiedHashes() throws IOException {
        return listHashes("SELECT chunk_hash FROM chunk WHERE verified = 1 ORDER BY last_access, chunk_hash");
    }

    private List<String> listHashes(String sql) throws IOException {
        List<String> hashes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                hashes.add(rows.getString(1));
            }
        } catch (SQLException e) {
            throw new IOException("cannot list chunks", e);
        }
        return List.copyOf(hashes);
    }

    /** Eviction candidates: verified, unreferenced, least recently used first. */
    public synchronized List<Entry> evictionCandidates(int limit) throws IOException {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        String sql = "SELECT chunk_hash, length, verified, last_access, ref_count, stored_at"
                + " FROM chunk WHERE verified = 1 AND ref_count = 0"
                + " ORDER BY last_access, chunk_hash LIMIT ?";
        List<Entry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    entries.add(toEntry(rows));
                }
            }
        } catch (SQLException e) {
            throw new IOException("cannot list eviction candidates", e);
        }
        return List.copyOf(entries);
    }

    public synchronized void remove(String sha256Hex) throws IOException {
        String hash = requireHash(sha256Hex);
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM chunk WHERE chunk_hash = ?")) {
            statement.setString(1, hash);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("cannot remove chunk " + hash, e);
        }
    }

    /** Total bytes of verified chunks. Quota policy in Phase 7 reads this. */
    public synchronized long verifiedBytes() throws IOException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT COALESCE(SUM(length), 0) FROM chunk WHERE verified = 1");
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IOException("cannot sum chunk bytes", e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IOException("cannot close chunk index", e);
        }
    }

    private void update(String sql, String sha256Hex, long timestamp) throws IOException {
        String hash = requireHash(sha256Hex);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, timestamp);
            statement.setString(2, hash);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("cannot update chunk " + hash, e);
        }
    }

    private long now() {
        return clock.instant().getEpochSecond();
    }

    private static Entry toEntry(ResultSet rows) throws SQLException {
        return new Entry(
                rows.getString(1),
                rows.getLong(2),
                rows.getInt(3) != 0,
                Instant.ofEpochSecond(rows.getLong(4)),
                rows.getInt(5),
                rows.getString(6));
    }

    private static String requireHash(String sha256Hex) {
        String hash = Objects.requireNonNull(sha256Hex, "sha256Hex").trim().toLowerCase(Locale.ROOT);
        Hex.fromHex(hash);
        if (hash.length() != 64) {
            throw new IllegalArgumentException("sha256 must be 64 hex characters");
        }
        return hash;
    }

    public record Entry(
            String chunkHash,
            long length,
            boolean verified,
            Instant lastAccess,
            int refCount,
            String storedAt
    ) {
    }
}
