package com.prabin.swarmedge.benchmark;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

/**
 * One immutable paper run directory (blueprint §13.3, P10-03).
 *
 * <p>A directory that already contains {@code validation.json} with {@code passed: true}
 * is left alone. A failed attempt does not count as that record.
 */
public final class RunFolder {

    private final Path root;

    private RunFolder(Path root) {
        this.root = root;
    }

    public static RunFolder create(Path rawRoot, String runId) throws IOException {
        Objects.requireNonNull(rawRoot, "rawRoot");
        if (runId == null || runId.isBlank() || runId.contains("..") || runId.contains("/") || runId.contains("\\")) {
            throw new IllegalArgumentException("runId must be a single path segment");
        }
        Path root = rawRoot.resolve(runId);
        if (Files.isDirectory(root) && passed(root)) {
            throw new IllegalStateException("run already recorded: " + root);
        }
        Files.createDirectories(root.resolve("stdout"));
        return new RunFolder(root);
    }

    public Path root() {
        return root;
    }

    public void write(Path config, String gitCommit, String environmentJson, long seed,
                      List<String> events, List<String> peerMetrics, String summaryJson,
                      boolean passed, String validationDetail, String log) throws IOException {
        Files.copy(config, root.resolve("config.yaml"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(root.resolve("git_commit.txt"), gitCommit + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("environment.json"), environmentJson, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("seed.txt"), Long.toString(seed) + System.lineSeparator(), StandardCharsets.UTF_8);
        gzip(root.resolve("events.csv.gz"), events);
        gzip(root.resolve("peer_metrics.csv.gz"), peerMetrics);
        Files.writeString(root.resolve("summary.json"), summaryJson, StandardCharsets.UTF_8);
        String validation = "{\"passed\":" + passed + ",\"detail\":" + jsonString(validationDetail)
                + ",\"recordedAt\":\"" + Instant.now() + "\"}\n";
        Files.writeString(root.resolve("validation.json"), validation, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("stdout").resolve("runner.log"), log, StandardCharsets.UTF_8);
    }

    private static boolean passed(Path root) throws IOException {
        Path validation = root.resolve("validation.json");
        if (!Files.isRegularFile(validation)) {
            return false;
        }
        return Files.readString(validation).contains("\"passed\":true");
    }

    private static void gzip(Path file, List<String> lines) throws IOException {
        List<String> rows = lines == null || lines.isEmpty() ? List.of("note") : lines;
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            for (String line : rows) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
            }
        }
    }

    private static String jsonString(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
