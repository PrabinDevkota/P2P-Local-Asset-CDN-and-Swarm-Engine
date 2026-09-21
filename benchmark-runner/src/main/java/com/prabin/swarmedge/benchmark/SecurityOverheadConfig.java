package com.prabin.swarmedge.benchmark;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed form of {@code research/configs/b9-security-overhead.yaml}.
 *
 * <p>This is an input. It never contains an expected hash, signature, or HMAC time.
 */
public record SecurityOverheadConfig(
        String scenarioId,
        String baseline,
        int repetitions,
        long seed,
        int payloadBytes
) {

    public SecurityOverheadConfig {
        requireText(scenarioId, "scenarioId");
        requireText(baseline, "baseline");
        if (repetitions <= 0) {
            throw new IllegalArgumentException("run.repetitions must be positive");
        }
        if (payloadBytes <= 0) {
            throw new IllegalArgumentException("run.payloadBytes must be positive");
        }
    }

    public static SecurityOverheadConfig load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    static SecurityOverheadConfig parse(Reader reader) {
        Map<String, Object> root = safeYaml().load(reader);
        if (root == null) {
            throw new IllegalArgumentException("scenario file is empty");
        }
        Map<String, Object> run = section(root, "run");
        return new SecurityOverheadConfig(
                string(root, "scenarioId"),
                string(root, "baseline"),
                (int) number(run, "repetitions"),
                number(run, "seed"),
                (int) number(run, "payloadBytes"));
    }

    private static Yaml safeYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String name) {
        Object value = root.get(name);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("missing section: " + name);
        }
        return (Map<String, Object>) map;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing key: " + key);
        }
        return value.toString().trim();
    }

    private static long number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("key " + key + " must be a number");
        }
        return n.longValue();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
