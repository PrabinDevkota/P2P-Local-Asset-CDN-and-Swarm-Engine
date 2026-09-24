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
 * The factors every paper run must name (blueprint P10-01, §18.4).
 *
 * <p>This does not replace {@link B0ScenarioConfig} or {@link SwarmScenarioConfig}.
 * Those still decide how a transfer runs. This only checks that a committed scenario
 * names baseline, scale, network, churn, cache, seed, and repetitions, so a preserved
 * run can say which factor moved.
 */
public record PaperScenario(
        String scenarioId,
        String baseline,
        int peers,
        int clients,
        String networkProfile,
        int delayMillis,
        int jitterMillis,
        double lossPercent,
        String churn,
        String cache,
        long seed,
        int repetitions
) {

    public PaperScenario {
        requireText(scenarioId, "scenarioId");
        requireText(baseline, "baseline");
        requireText(networkProfile, "network.profile");
        requireText(churn, "churn");
        requireText(cache, "cacheState");
        if (peers < 0) {
            throw new IllegalArgumentException("scale.peers cannot be negative");
        }
        if (clients <= 0) {
            throw new IllegalArgumentException("scale.clients must be positive");
        }
        if (delayMillis < 0 || jitterMillis < 0) {
            throw new IllegalArgumentException("network delay and jitter cannot be negative");
        }
        if (lossPercent < 0 || lossPercent > 100) {
            throw new IllegalArgumentException("network.lossPercent must be between 0 and 100");
        }
        if (!cache.equals("cold") && !cache.equals("warm")) {
            throw new IllegalArgumentException("cacheState must be cold or warm");
        }
        if (repetitions <= 0) {
            throw new IllegalArgumentException("run.repetitions must be positive");
        }
    }

    public NetworkImpairment impairment() {
        return new NetworkImpairment(networkProfile, delayMillis, jitterMillis, lossPercent);
    }

    public static PaperScenario load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    @SuppressWarnings("unchecked")
    static PaperScenario parse(Reader reader) {
        Map<String, Object> root = safeYaml().load(reader);
        if (root == null) {
            throw new IllegalArgumentException("scenario file is empty");
        }
        Map<String, Object> scale = section(root, "scale");
        Map<String, Object> network = section(root, "network");
        Map<String, Object> run = section(root, "run");
        return new PaperScenario(
                string(root, "scenarioId"),
                string(root, "baseline"),
                (int) number(scale, "peers"),
                (int) number(scale, "clients"),
                string(network, "profile"),
                (int) number(network, "delayMillis"),
                (int) number(network, "jitterMillis"),
                decimal(network, "lossPercent"),
                churn(root),
                string(root, "cacheState"),
                number(run, "seed"),
                (int) number(run, "repetitions"));
    }

    private static String churn(Map<String, Object> root) {
        Object value = root.get("churn");
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        if (value instanceof Map<?, ?> map) {
            Object fractions = map.get("killFractions");
            if (fractions != null) {
                return fractions.toString();
            }
            Object mode = map.get("mode");
            if (mode != null) {
                return mode.toString();
            }
        }
        throw new IllegalArgumentException("missing churn");
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
        if (value == null || value.toString().isBlank()) {
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

    private static double decimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("key " + key + " must be a number");
        }
        return n.doubleValue();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
