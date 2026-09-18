package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.manifest.CacheEvictor;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed form of a B0 or B4 scenario file such as {@code research/configs/b0-origin-only.yaml}.
 *
 * <p>A config describes an experiment; it never describes an expected result.
 * Every field here is an input that must be preserved with the run so the run
 * can be recreated (blueprint §13.3, §19.1).
 */
public record B0ScenarioConfig(
        String scenarioId,
        String baseline,
        String productId,
        String version,
        String fileName,
        long sizeBytes,
        long chunkSizeBytes,
        int repetitions,
        long seed,
        boolean coldCache,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration connectTimeout,
        Duration requestTimeout,
        CacheEvictor.Settings cache
) {

    public B0ScenarioConfig {
        requireText(scenarioId, "scenarioId");
        requireText(baseline, "baseline");
        requireText(productId, "asset.productId");
        requireText(version, "asset.version");
        requireText(fileName, "asset.fileName");
        requirePositive(sizeBytes, "asset.sizeBytes");
        requirePositive(chunkSizeBytes, "asset.chunkSizeBytes");
        requirePositive(repetitions, "run.repetitions");
        requirePositive(maxAttempts, "origin.maxAttempts");
        Objects.requireNonNull(initialBackoff, "origin.initialBackoffMillis");
        Objects.requireNonNull(maxBackoff, "origin.maxBackoffMillis");
        Objects.requireNonNull(connectTimeout, "origin.connectTimeoutMillis");
        Objects.requireNonNull(requestTimeout, "origin.requestTimeoutMillis");
        cache = cache == null ? CacheEvictor.Settings.unlimited() : cache;
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("asset.fileName must be a plain name");
        }
    }

    public static B0ScenarioConfig load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    static B0ScenarioConfig parse(Reader reader) {
        Map<String, Object> root = safeYaml().load(reader);
        if (root == null) {
            throw new IllegalArgumentException("scenario file is empty");
        }
        Map<String, Object> asset = section(root, "asset");
        Map<String, Object> run = section(root, "run");
        Map<String, Object> origin = section(root, "origin");
        return new B0ScenarioConfig(
                string(root, "scenarioId"),
                string(root, "baseline"),
                string(asset, "productId"),
                string(asset, "version"),
                string(asset, "fileName"),
                number(asset, "sizeBytes"),
                number(asset, "chunkSizeBytes"),
                (int) number(run, "repetitions"),
                number(run, "seed"),
                bool(run, "coldCache"),
                (int) number(origin, "maxAttempts"),
                millis(origin, "initialBackoffMillis"),
                millis(origin, "maxBackoffMillis"),
                millis(origin, "connectTimeoutMillis"),
                millis(origin, "requestTimeoutMillis"),
                cache(root));
    }

    private static CacheEvictor.Settings cache(Map<String, Object> root) {
        Object value = root.get("cache");
        if (value == null) {
            return CacheEvictor.Settings.unlimited();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("section cache must be a mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cache = (Map<String, Object>) map;
        return new CacheEvictor.Settings(number(cache, "maxBytes"), number(cache, "minFreeBytes"));
    }

    /** Scenario files are repository content, but a loader that can build arbitrary classes is not. */
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

    private static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Boolean b)) {
            throw new IllegalArgumentException("key " + key + " must be true or false");
        }
        return b;
    }

    private static Duration millis(Map<String, Object> map, String key) {
        long value = number(map, key);
        if (value < 0) {
            throw new IllegalArgumentException("key " + key + " must be non-negative");
        }
        return Duration.ofMillis(value);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
