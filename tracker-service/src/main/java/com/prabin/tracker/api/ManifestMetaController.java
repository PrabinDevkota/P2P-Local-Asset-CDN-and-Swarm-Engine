package com.prabin.tracker.api;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.tracker.store.ManifestMetaStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Index of where a release manifest is published. This does not fetch, parse, or
 * trust the document at {@code manifestUrl}.
 */
@RestController
public final class ManifestMetaController {

    private static final Pattern TEXT = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final ManifestMetaStore store;

    public ManifestMetaController(ManifestMetaStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @PutMapping("/api/v1/assets/{assetId}/manifest")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void put(@PathVariable("assetId") String assetId, @RequestBody MetaBody body) {
        AssetId asset = parseAsset(assetId);
        store.save(asset, validate(body));
    }

    @GetMapping("/api/v1/assets/{assetId}/manifest")
    public ManifestMetaStore.ManifestMeta get(@PathVariable("assetId") String assetId) {
        AssetId asset = parseAsset(assetId);
        return store.find(asset).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "no manifest metadata"));
    }

    static ManifestMetaStore.ManifestMeta validate(MetaBody body) {
        if (body == null) {
            throw new IllegalArgumentException("manifest metadata is required");
        }
        String productId = requireLabel(body.productId(), "productId");
        String version = requireLabel(body.version(), "version");
        String url = requireUrl(body.manifestUrl());
        String publishedAt = requireInstant(body.publishedAt());
        return new ManifestMetaStore.ManifestMeta(productId, version, url, publishedAt);
    }

    private static AssetId parseAsset(String hex) {
        try {
            return AssetId.fromHex(hex);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("assetId must be 64 hex characters");
        }
    }

    private static String requireLabel(String value, String name) {
        if (value == null || !TEXT.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String requireUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("manifestUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("manifestUrl must be an http(s) URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null) {
            throw new IllegalArgumentException("manifestUrl must be an http(s) URL");
        }
        return uri.toString();
    }

    private static String requireInstant(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("publishedAt is required");
        }
        try {
            return Instant.parse(value.trim()).toString();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("publishedAt must be a UTC instant");
        }
    }

    public record MetaBody(String productId, String version, String manifestUrl, String publishedAt) {
    }
}
