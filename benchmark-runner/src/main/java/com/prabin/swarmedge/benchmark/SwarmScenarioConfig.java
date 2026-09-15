package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.peer.laps.LapsWeights;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Parsed form of a swarm scenario file (blueprint P5-04, P6-05).
 *
 * <p>One shape for three baselines. B1, B2, and B3 differ only in their
 * {@code scheduler} section, which is what lets the comparison between them mean
 * something: if the asset, the seed, or the pipeline depth moved as well, any difference
 * in the results would have more than one possible cause and the experiment would not
 * isolate the scheduler at all.
 *
 * <p>A config describes an experiment; it never describes an expected result. Every
 * field here is an input that has to be preserved with the run so the run can be
 * recreated (blueprint §13.3, §19.1), including the seed that fixes the rarest-first
 * tie-break, the LAPS tie-break, and which peers are killed for churn.
 */
public record SwarmScenarioConfig(
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
        Duration stallTimeout,
        int maxAttemptsPerBlock,
        SourcePolicy sourcePolicy,
        LapsWeights lapsWeights,
        int endgameThresholdBlocks,
        List<Double> killFractions,
        boolean everyPeerHoldsEverything
) {

    /**
     * How a run picks which peer to ask, which is the only thing that separates the
     * three baselines (§8.1 decision B).
     */
    public enum SourcePolicy {

        /** B1: dial in whatever order discovery returned. No source preference at all. */
        AS_DISCOVERED,

        /** B2: nearest first and nothing else, so LAPS has a locality-only control. */
        LOCALITY_ONLY,

        /** B3: the full §8.2 score, locality plus the measured terms. */
        LAPS;

        /** The weights this policy implies, or empty when it does not score at all. */
        public Optional<LapsWeights> impliedWeights() {
            return switch (this) {
                case AS_DISCOVERED -> Optional.empty();
                case LOCALITY_ONLY -> Optional.of(LapsWeights.localityOnly());
                case LAPS -> Optional.of(LapsWeights.defaults());
            };
        }
    }

    public SwarmScenarioConfig {
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
        Objects.requireNonNull(stallTimeout, "swarm.stallTimeoutMillis");
        killFractions = List.copyOf(Objects.requireNonNull(killFractions, "churn.killFractions"));
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("asset.fileName must be a plain name");
        }
        if (blockSizeBytes > chunkSizeBytes) {
            throw new IllegalArgumentException("swarm.blockSizeBytes cannot exceed asset.chunkSizeBytes");
        }
        if (stallTimeout.compareTo(blockTimeout) <= 0) {
            throw new IllegalArgumentException("swarm.stallTimeoutMillis must outlast swarm.blockTimeoutMillis,"
                    + " or one slow block reads as a dead swarm");
        }
        Objects.requireNonNull(sourcePolicy, "scheduler.sourcePolicy");
        if (endgameThresholdBlocks < 0) {
            throw new IllegalArgumentException("scheduler.endgameThresholdBlocks cannot be negative");
        }
        if (sourcePolicy == SourcePolicy.AS_DISCOVERED && lapsWeights != null) {
            throw new IllegalArgumentException("scheduler.lapsWeights has no meaning under "
                    + SourcePolicy.AS_DISCOVERED + "; a baseline that does not score peers must"
                    + " not carry weights, or a run record implies a policy it did not use");
        }
        if (sourcePolicy != SourcePolicy.AS_DISCOVERED && lapsWeights == null) {
            throw new IllegalArgumentException("scheduler.sourcePolicy " + sourcePolicy
                    + " needs scheduler.lapsWeights");
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

    public static SwarmScenarioConfig load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    static SwarmScenarioConfig parse(Reader reader) {
        Map<String, Object> root = safeYaml().load(reader);
        if (root == null) {
            throw new IllegalArgumentException("scenario file is empty");
        }
        Map<String, Object> asset = section(root, "asset");
        Map<String, Object> run = section(root, "run");
        Map<String, Object> swarm = section(root, "swarm");
        Map<String, Object> scheduler = section(root, "scheduler");
        Map<String, Object> churn = section(root, "churn");
        SourcePolicy policy = policy(scheduler);
        return new SwarmScenarioConfig(
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
                millis(swarm, "stallTimeoutMillis"),
                (int) number(swarm, "maxAttemptsPerBlock"),
                policy,
                weights(scheduler, policy),
                (int) number(scheduler, "endgameThresholdBlocks"),
                fractions(churn, "killFractions"),
                bool(churn, "everyPeerHoldsEverything"));
    }

    private static SourcePolicy policy(Map<String, Object> scheduler) {
        String name = string(scheduler, "sourcePolicy");
        try {
            return SourcePolicy.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("scheduler.sourcePolicy must be one of "
                    + Arrays.toString(SourcePolicy.values()) + ", got " + name, e);
        }
    }

    /**
     * Weights are spelled out in the file rather than taken from the policy name. A run
     * record that only said "LAPS" would not say which weights produced it, and the
     * whole point of a sensitivity sweep is that they vary.
     */
    private static LapsWeights weights(Map<String, Object> scheduler, SourcePolicy policy) {
        Object value = scheduler.get("lapsWeights");
        if (value == null) {
            return policy == SourcePolicy.AS_DISCOVERED ? null : defaultWeightsFor(policy);
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("scheduler.lapsWeights must be a mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> weights = (Map<String, Object>) map;
        return new LapsWeights(
                fraction(weights, "locality"),
                fraction(weights, "throughput"),
                fraction(weights, "rtt"),
                fraction(weights, "capacity"),
                fraction(weights, "health"));
    }

    private static LapsWeights defaultWeightsFor(SourcePolicy policy) {
        return policy.impliedWeights().orElseThrow();
    }

    private static double fraction(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("scheduler.lapsWeights." + key + " must be a number");
        }
        return n.doubleValue();
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
