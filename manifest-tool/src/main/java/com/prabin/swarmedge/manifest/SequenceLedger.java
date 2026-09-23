package com.prabin.swarmedge.manifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Highest-seen release sequence per product, stored so a process restart still
 * refuses a rollback (blueprint P9-01).
 *
 * <p>One line per product: {@code sequence productId}. The sequence is only ever
 * raised. A lower value on disk is ignored in favour of the higher one already
 * loaded.
 */
public final class SequenceLedger {

    private final Path file;
    private final ConcurrentHashMap<String, Long> highest = new ConcurrentHashMap<>();

    private SequenceLedger(Path file) {
        this.file = file;
    }

    public static SequenceLedger open(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        SequenceLedger ledger = new SequenceLedger(file);
        if (Files.isRegularFile(file)) {
            ledger.load(Files.readString(file, StandardCharsets.UTF_8));
        }
        return ledger;
    }

    public OptionalLong get(String productId) {
        Long seen = highest.get(Objects.requireNonNull(productId, "productId"));
        return seen == null ? OptionalLong.empty() : OptionalLong.of(seen);
    }

    public Map<String, Long> snapshot() {
        return Map.copyOf(highest);
    }

    /** Remember {@code sequence} when it is higher than what is already stored. */
    public synchronized void raise(String productId, long sequence) throws IOException {
        Objects.requireNonNull(productId, "productId");
        if (productId.isBlank() || productId.indexOf('\n') >= 0 || productId.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("productId must be a single line");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        Long seen = highest.get(productId);
        if (seen != null && sequence <= seen) {
            return;
        }
        highest.put(productId, sequence);
        write();
    }

    private void load(String text) {
        int lineNo = 0;
        for (String raw : text.split("\n", -1)) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space <= 0 || space == line.length() - 1) {
                throw new IllegalArgumentException("sequence ledger line " + lineNo + " is malformed");
            }
            long sequence;
            try {
                sequence = Long.parseLong(line.substring(0, space));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("sequence ledger line " + lineNo + " is malformed");
            }
            if (sequence < 0) {
                throw new IllegalArgumentException("sequence ledger line " + lineNo + " is malformed");
            }
            String productId = line.substring(space + 1).trim();
            if (productId.isEmpty()) {
                throw new IllegalArgumentException("sequence ledger line " + lineNo + " is malformed");
            }
            highest.merge(productId, sequence, Math::max);
        }
    }

    private void write() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        highest.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> lines.add(entry.getValue() + " " + entry.getKey()));
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
