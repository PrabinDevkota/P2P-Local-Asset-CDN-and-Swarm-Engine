package com.prabin.tracker.store;

import com.prabin.swarmedge.common.id.AssetId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.Optional;

/**
 * {@code asset:{assetId}:manifest-meta}. A URL and version label only.
 * The signed manifest file is the trust root; this key is an index.
 */
public interface ManifestMetaStore {

    void save(AssetId assetId, ManifestMeta meta);

    Optional<ManifestMeta> find(AssetId assetId);

    static String key(AssetId assetId) {
        return "asset:" + Objects.requireNonNull(assetId, "assetId").toHex() + ":manifest-meta";
    }

    record ManifestMeta(String productId, String version, String manifestUrl, String publishedAt) {
        public ManifestMeta {
            Objects.requireNonNull(productId, "productId");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(manifestUrl, "manifestUrl");
            Objects.requireNonNull(publishedAt, "publishedAt");
        }
    }

    @Service
    final class Redis implements ManifestMetaStore {
        private static final JsonMapper JSON = JsonMapper.builder().build();

        private final StringRedisTemplate redis;

        public Redis(StringRedisTemplate redis) {
            this.redis = Objects.requireNonNull(redis, "redis");
        }

        @Override
        public void save(AssetId assetId, ManifestMeta meta) {
            redis.opsForValue().set(key(assetId), JSON.writeValueAsString(meta));
        }

        @Override
        public Optional<ManifestMeta> find(AssetId assetId) {
            String raw = redis.opsForValue().get(key(assetId));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(JSON.readValue(raw, ManifestMeta.class));
        }
    }
}
