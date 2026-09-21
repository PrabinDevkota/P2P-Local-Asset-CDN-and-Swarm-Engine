package com.prabin.tracker.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P9-03: tokens and private keys must not appear in production log statements.
 *
 * <p>A line that merely names the missing-config warning is allowed. A line that
 * interpolates a token, secret, or PEM is not.
 */
class SecretLogAuditTest {

    private static final Pattern LOG_CALL = Pattern.compile(
            "\\b(log|LOG|logger)\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(token|secret|privateKey|private\\.pem|Authorization)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> ALLOWED_SNIPPETS = List.of(
            "token-secret is not set"
    );

    @Test
    void productionLogsDoNotPrintTokensOrKeys() throws Exception {
        Path root = repoRoot();
        List<String> offenders = new ArrayList<>();
        String[] modules = {
                "common", "protocol", "manifest-tool", "origin-fixture",
                "peer-agent", "tracker-service", "benchmark-runner"
        };
        for (String module : modules) {
            Path javaRoot = root.resolve(module).resolve("src").resolve("main").resolve("java");
            if (!Files.isDirectory(javaRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(javaRoot)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    scan(root.relativize(file).toString().replace('\\', '/'), Files.readAllLines(file), offenders);
                }
            }
        }
        assertThat(offenders)
                .as("production log calls must not mention tokens, secrets, or private keys")
                .isEmpty();
    }

    private static void scan(String relative, List<String> lines, List<String> offenders) {
        String pending = "";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*")) {
                continue;
            }
            pending = pending.isEmpty() ? stripped : pending + " " + stripped;
            if (!pending.contains(";")) {
                continue;
            }
            String statement = pending;
            pending = "";
            if (!LOG_CALL.matcher(statement).find()) {
                continue;
            }
            if (!FORBIDDEN.matcher(statement).find()) {
                continue;
            }
            if (allowed(statement)) {
                continue;
            }
            offenders.add(relative + ":" + (i + 1) + " " + statement);
        }
    }

    private static boolean allowed(String statement) {
        String lower = statement.toLowerCase(Locale.ROOT);
        for (String snippet : ALLOWED_SNIPPETS) {
            if (lower.contains(snippet.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(here.resolve("tracker-service")) && Files.isRegularFile(here.resolve("pom.xml"))) {
            return here;
        }
        Path parent = here.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("pom.xml"))) {
            return parent;
        }
        throw new IllegalStateException("cannot locate repository root from " + here);
    }
}
