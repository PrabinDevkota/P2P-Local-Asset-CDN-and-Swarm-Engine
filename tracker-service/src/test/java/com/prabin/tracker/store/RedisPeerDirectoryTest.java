package com.prabin.tracker.store;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisPeerDirectoryTest {

    @Test
    void storedJsonRoundTripKeepsPeerFields() {
        PeerRecord peer = peer("11", "10.0.0.1");
        RedisPeerDirectory.Stored stored = RedisPeerDirectory.Stored.from(peer);
        JsonMapper json = JsonMapper.builder().build();
        RedisPeerDirectory.Stored parsed = json.readValue(json.writeValueAsString(stored), RedisPeerDirectory.Stored.class);
        PeerRecord back = parsed.toRecord(peer.peerId());
        assertThat(back.peerId()).isEqualTo(peer.peerId());
        assertThat(back.ip()).isEqualTo(peer.ip());
        assertThat(back.port()).isEqualTo(peer.port());
        assertThat(back.siteId()).isEqualTo(peer.siteId());
        assertThat(back.networkGroupId()).isEqualTo(peer.networkGroupId());
        assertThat(back.bitCount()).isEqualTo(peer.bitCount());
        assertThat(back.bits()).containsExactly(peer.bits());
    }

    @Test
    void saveRoundTripAndFieldExpireDoesNotDropOtherPeers() throws Exception {
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

                AssetId asset = AssetId.fromHex("a".repeat(64));
                PeerRecord keep = peer("11", "10.0.0.1");
                PeerRecord drop = peer("22", "10.0.0.2");
                directory.save(asset, keep);
                directory.save(asset, drop);

                assertThat(directory.list(asset)).extracting(p -> p.peerId().toHex())
                        .containsExactlyInAnyOrder(keep.peerId().toHex(), drop.peerId().toHex());

                template.execute((RedisCallback<Object>) connection -> {
                    connection.execute(
                            "HEXPIRE",
                            RedisPeerDirectory.key(asset).getBytes(StandardCharsets.US_ASCII),
                            "1".getBytes(StandardCharsets.US_ASCII),
                            "FIELDS".getBytes(StandardCharsets.US_ASCII),
                            "1".getBytes(StandardCharsets.US_ASCII),
                            drop.peerId().toHex().getBytes(StandardCharsets.US_ASCII));
                    return null;
                });
                Thread.sleep(1500);

                assertThat(directory.list(asset)).extracting(p -> p.peerId().toHex())
                        .containsExactly(keep.peerId().toHex());
            } finally {
                factory.destroy();
            }
        }
    }

    private static LettuceConnectionFactory factory(GenericContainer<?> redis) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                redis.getHost(), redis.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static PeerRecord peer(String twoHex, String ip) {
        return new PeerRecord(PeerId.fromHex(twoHex.repeat(16)), ip, 9091, "site-a", "ng-1", 8, new byte[] {(byte) 0x80});
    }
}
