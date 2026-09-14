package com.prabin.swarmedge.benchmark;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed form of a B1 scenario file such as {@code research/configs/b1-basic-swarm.yaml}.
 *
 * <p>A config describes an experiment; it never describes an expected result. Every
 * field here is an input that has to be preserved with the run so the run can be
 * recreated (blueprint §13.3, §19.1), including the seed that fixes the rarest-first
 * tie-break and which peers are killed for churn.
 */
public record B1ScenarioConfig(
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
        int maxPeers,
        int seederCount,
        int blockSizeBytes,
        int outstandingRequestsPerPeer,
        Duration blockTimeout,
        Duration handshakeTimeout,
        Duration connectTimeout,
        int maxAttemptsPerBlock,
        List<Double> killFractions,
        boolean everyPeerHoldsEverything
) {

    public B1ScenarioConfig {
        requireText(scenarioId, "scenarioId");
        requireText(baseline, "baseline");
        requireText(productId, "asset.productId");
        requireText(version, "asset.version");
        requireText(fileName, "asset.fileName");
        requirePositive(sizeBytes, "asset.sizeBytes");
        requirePositive(chunkSizeBytes, "asset.chunkSizeBytes");
        requirePositive(repetitions, "run.repetitions");
        requirePositive(maxPeers, "swarm.maxPeers");
        requirePositive(seederCount, "swarm.seederCount");
        requirePositive(blockSizeBytes, "swarm.blockSizeBytes");
        requirePositive(outstandingRequestsPerPeer, "swarm.outstandingRequestsPerPeer");
        requirePositive(maxAttemptsPerBlock, "swarm.maxAttemptsPerBlock");
        Objects.requireNonNull(blockTimeout, "swarm.blockTimeoutMillis");
        Objects.requireNonNull(handshakeTimeout, "swarm.handshakeTimeoutMillis");
        Objects.requireNonNull(connectTimeout, "swarm.connectTimeoutMillis");
        killFractions = List.copyOf(Objects.requireNonNull(killFractions, "churn.killFractions"));
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("asset.fileName must be a plain name");
        }
        if (blockSizeBytes > chunkSizeBytes) {
            throw new IllegalArgumentException("swarm.blockSizeBytes cannot exceed asset.chunkSizeBytes");
        }
        if (killFractions.isEmpty()) {
            throw new IllegalArgumentException("churn.killFractions must list at least one share");
        }
        for (double fraction : killFractions) {
            if (fraction < 0 || fraction >= 1) {
                throw new IllegalArgumentException(
                        "churn.killFractions must be at least 0 and below 1, got " + fraction);
            }
        }
    }

    /** How many seeders a given kill share takes out, never all of them. */
    public int peersToKill(double fraction) {
        if (!killFractions.contains(fraction)) {
            throw new IllegalArgumentException("not a configured kill share: " + fraction);
        }
        return Math.min(seederCount - 1, (int) Math.floor(seederCount * fraction));
    }

    public static B1ScenarioConfig load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    static B1ScenarioConfig parse(Reader reader) {
        Map<String, Object> root = safeYaml().load(reader);
        if (root == null) {
            throw new IllegalArgumentException("scenario file is empty");
        }
        Map<String, Object> asset = section(root, "asset");
        Map<String, Object> run = section(root, "run");
        Map<String, Object> swarm = section(root, "swarm");
        Map<String, Object> churn = section(root, "churn");
        return new B1ScenarioConfig(
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
                (int) number(swarm, "maxPeers"),
                (int) number(swarm, "seederCount"),
                (int) number(swarm, "blockSizeBytes"),
                (int) number(swarm, "outstandingRequestsPerPeer"),
                millis(swarm, "blockTimeoutMillis"),
                millis(swarm, "handshakeTimeoutMillis"),
                millis(swarm, "connectTimeoutMillis"),
                (int) number(swarm, "maxAttemptsPerBlock"),
                fractions(churn, "killFractions"),
                bool(churn, "everyPeerHoldsEverything"));
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

    private static List<Double> fractions(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("key " + key + " must be a list");
        }
        List<Double> fractions = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Number n)) {
                throw new IllegalArgumentException("key " + key + " must list numbers");
            }
            fractions.add(n.doubleValue());
        }
        return fractions;
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
        if (value <= 0) {
            throw new IllegalArgumentException("key " + key + " must be positive");
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
