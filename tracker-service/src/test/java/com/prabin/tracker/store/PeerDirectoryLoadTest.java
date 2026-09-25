package com.prabin.tracker.store;

import com.prabin.swarmedge.common.Defaults;
import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.tracker.rank.LocalityRanker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.IntegerOutput;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-05: control-plane behaviour at 250 peer records.
 *
 * <p>Latency and state size are <em>recorded</em>, never asserted against a
 * threshold. A number produced on a developer laptop inside a unit test is not
 * an experimental result, and the blueprint forbids dressing one up as one.
 * The assertions here are about correctness only: the candidate list stays
 * bounded, excludes the requester, and keeps its locality order at scale.
 */
class PeerDirectoryLoadTest {

    private static final Logger log = LoggerFactory.getLogger(PeerDirectoryLoadTest.class);

    private static final int PEER_COUNT = 250;
    private static final int SITES = 5;
    private static final int GROUPS_PER_SITE = 4;
    private static final int MEASURED_QUERIES = 200;
    private static final int WARMUP_QUERIES = 50;

    private static final AssetId ASSET = AssetId.fromHex("a".repeat(64));

    @Test
    void discoveryStaysBoundedAndOrderedWith250Records() {
        PeerDirectory directory = new MemoryPeerDirectory();
        List<PeerRecord> peers = generatePeers();
        peers.forEach(peer -> directory.save(ASSET, peer));
        PeerRecord self = peers.getFirst();

        assertThat(directory.list(ASSET)).hasSize(PEER_COUNT);

        List<PeerRecord> ranked = discover(directory, self);

        assertThat(ranked).hasSize(Defaults.TRACKER_CANDIDATE_LIMIT);
        assertThat(ranked).noneMatch(peer -> peer.peerId().equals(self.peerId()));
        assertThat(ranked).allSatisfy(peer -> assertThat(peer.siteId()).isEqualTo(self.siteId()));

        long[] nanos = measure(directory, self);
        log.info("discovery over {} records: p50={}us p95={}us p99={}us candidates={}",
                PEER_COUNT, micros(nanos, 50), micros(nanos, 95), micros(nanos, 99), ranked.size());
    }

    @Test
    void everySiteIsReachableSoRankingIsNotStuckOnOneNeighbourhood() {
        PeerDirectory directory = new MemoryPeerDirectory();
        List<PeerRecord> peers = generatePeers();
        peers.forEach(peer -> directory.save(ASSET, peer));

        for (int site = 0; site < SITES; site++) {
            PeerRecord self = peers.get(site);
            List<PeerRecord> ranked = discover(directory, self);

            assertThat(ranked).isNotEmpty();
            assertThat(ranked.getFirst().siteId()).isEqualTo(self.siteId());
        }
    }

    @Test
    void redisHolds250RecordsAndReportsItsStateSize() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());
        try (GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                .withExposedPorts(6379)
                .withStartupTimeout(Duration.ofSeconds(30))) {
            redis.start();
            LettuceConnectionFactory factory = factory(redis);
            try {
                StringRedisTemplate template = new StringRedisTemplate(factory);
                template.afterPropertiesSet();
                RedisPeerDirectory directory = new RedisPeerDirectory(template);
                List<PeerRecord> peers = generatePeers();

                peers.forEach(peer -> directory.save(ASSET, peer));

                assertThat(directory.list(ASSET)).hasSize(PEER_COUNT);
                PeerRecord self = peers.getFirst();
                assertThat(discover(directory, self)).hasSize(Defaults.TRACKER_CANDIDATE_LIMIT);

                long[] nanos = measure(directory, self);
                long stateBytes = memoryUsage(template);
                log.info("redis discovery over {} records: p50={}us p95={}us p99={}us stateBytes={}",
                        PEER_COUNT, micros(nanos, 50), micros(nanos, 95), micros(nanos, 99),
                        stateBytes);
                assertThat(stateBytes).isPositive();
            } finally {
                factory.destroy();
            }
        }
    }

    private static List<PeerRecord> discover(PeerDirectory directory, PeerRecord self) {
        return LocalityRanker.rank(directory.list(ASSET), self.siteId(), self.networkGroupId(),
                self.peerId(), Defaults.TRACKER_CANDIDATE_LIMIT);
    }

    private static long[] measure(PeerDirectory directory, PeerRecord self) {
        for (int i = 0; i < WARMUP_QUERIES; i++) {
            discover(directory, self);
        }
        long[] nanos = new long[MEASURED_QUERIES];
        for (int i = 0; i < MEASURED_QUERIES; i++) {
            long startedAt = System.nanoTime();
            discover(directory, self);
            nanos[i] = System.nanoTime() - startedAt;
        }
        Arrays.sort(nanos);
        return nanos;
    }

    private static long micros(long[] sortedNanos, int percentile) {
        int index = Math.min(sortedNanos.length - 1, (int) Math.ceil(percentile / 100.0 * sortedNanos.length) - 1);
        return sortedNanos[Math.max(index, 0)] / 1_000;
    }

    /**
     * {@code MEMORY USAGE} replies with an integer. The generic {@code execute(String, byte[]...)}
     * path decodes every reply as a bulk string, which Lettuce rejects for this command.
     */
    private static long memoryUsage(StringRedisTemplate template) {
        byte[] key = RedisPeerDirectory.key(ASSET).getBytes(StandardCharsets.US_ASCII);
        RedisConnectionFactory factory = template.getConnectionFactory();
        if (factory == null) {
            throw new IllegalStateException("redis connection factory is missing");
        }
        try (RedisConnection connection = factory.getConnection()) {
            if (!(connection instanceof LettuceConnection lettuce)) {
                throw new IllegalStateException("expected a Lettuce connection");
            }
            Object usage = lettuce.execute(
                    "MEMORY",
                    new IntegerOutput<>(ByteArrayCodec.INSTANCE),
                    "USAGE".getBytes(StandardCharsets.US_ASCII),
                    key);
            if (!(usage instanceof Number number)) {
                throw new IllegalStateException("MEMORY USAGE returned " + usage);
            }
            return number.longValue();
        }
    }

    /** Peers spread over sites and network groups so ranking has real tiers to sort. */
    private static List<PeerRecord> generatePeers() {
        List<PeerRecord> peers = new ArrayList<>(PEER_COUNT);
        for (int i = 0; i < PEER_COUNT; i++) {
            String site = "site-" + (i % SITES);
            String group = "ng-" + (i % SITES) + "-" + (i / SITES % GROUPS_PER_SITE);
            peers.add(new PeerRecord(
                    PeerId.fromHex(String.format("%032x", i + 1)),
                    "10.0." + (i / 256) + "." + (i % 256),
                    9000 + (i % 1000),
                    site,
                    group,
                    8,
                    new byte[] {(byte) 0xFF}));
        }
        return peers;
    }

    private static LettuceConnectionFactory factory(GenericContainer<?> redis) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                redis.getHost(), redis.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static final class MemoryPeerDirectory implements PeerDirectory {
        private final Map<String, PeerRecord> peers = new ConcurrentHashMap<>();

        @Override
        public void save(AssetId assetId, PeerRecord peer) {
            peers.put(assetId.toHex() + "/" + peer.peerId().toHex(), peer);
        }

        @Override
        public List<PeerRecord> list(AssetId assetId) {
            String prefix = assetId.toHex() + "/";
            return peers.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .map(Map.Entry::getValue)
                    .toList();
        }
    }
}
