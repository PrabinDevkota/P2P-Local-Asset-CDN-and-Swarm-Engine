package com.prabin.tracker.store;

import com.prabin.swarmedge.common.Defaults;
import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.Hex;
import com.prabin.swarmedge.common.id.PeerId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redis HASH {@code swarm:{assetId}:peers} with per-field HEXPIRE (45s).
 * One peer expiry must not delete the rest of the swarm.
 */
@Service
public final class RedisPeerDirectory implements PeerDirectory {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringRedisTemplate redis;

    public RedisPeerDirectory(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    public static String key(AssetId assetId) {
        return "swarm:" + assetId.toHex() + ":peers";
    }

    @Override
    public void save(AssetId assetId, PeerRecord peer) {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(peer, "peer");
        String redisKey = key(assetId);
        String field = peer.peerId().toHex();
        byte[] json = JSON.writeValueAsString(Stored.from(peer)).getBytes(StandardCharsets.UTF_8);
        redis.execute((RedisCallback<Object>) connection -> {
            byte[] keyBytes = bytes(redisKey);
            byte[] fieldBytes = bytes(field);
            connection.hashCommands().hSet(keyBytes, fieldBytes, json);
            connection.execute(
                    "HEXPIRE",
                    keyBytes,
                    bytes(Integer.toString(Defaults.PEER_TTL_SECONDS)),
                    bytes("FIELDS"),
                    bytes("1"),
                    fieldBytes);
            return null;
        });
    }

    @Override
    public List<PeerRecord> list(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId");
        Map<Object, Object> entries = redis.opsForHash().entries(key(assetId));
        List<PeerRecord> peers = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            try {
                PeerId peerId = PeerId.fromHex(String.valueOf(entry.getKey()));
                Stored stored = JSON.readValue(String.valueOf(entry.getValue()), Stored.class);
                peers.add(stored.toRecord(peerId));
            } catch (RuntimeException ignored) {
                // skip poison fields; do not fail the whole candidate list
            }
        }
        return List.copyOf(peers);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    record Stored(
            String ip,
            int port,
            String siteId,
            String networkGroupId,
            int bitCount,
            String bits,
            Integer capabilities,
            Long uploadBudget,
            Double uploadLoad
    ) {
        static Stored from(PeerRecord peer) {
            return new Stored(
                    peer.ip(),
                    peer.port(),
                    peer.siteId(),
                    peer.networkGroupId(),
                    peer.bitCount(),
                    Hex.toLowerHex(peer.bits()),
                    peer.capabilities(),
                    peer.uploadBudgetBytesPerSecond(),
                    peer.uploadLoad().isPresent() ? peer.uploadLoad().getAsDouble() : null);
        }

        PeerRecord toRecord(PeerId peerId) {
            byte[] decoded = bits == null || bits.isBlank() ? new byte[0] : Hex.fromHex(bits);
            java.util.OptionalDouble load = uploadLoad == null
                    ? java.util.OptionalDouble.empty()
                    : java.util.OptionalDouble.of(uploadLoad);
            return new PeerRecord(
                    peerId,
                    ip,
                    port,
                    siteId,
                    networkGroupId,
                    bitCount,
                    decoded,
                    capabilities == null ? 0 : capabilities,
                    uploadBudget == null ? 0L : uploadBudget,
                    load);
        }
    }
}
