package com.prabin.swarmedge.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class ManifestJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ManifestJson() {
    }

    public static ReleaseManifest parse(String json) {
        try {
            ReleaseManifest manifest = MAPPER.readValue(json, ReleaseManifest.class);
            ManifestValidator.validate(manifest);
            return manifest;
        } catch (IOException e) {
            throw new ManifestValidationException("invalid JSON: " + e.getMessage());
        }
    }

    public static ReleaseManifest parse(InputStream in) {
        try {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Full document including signature. Canonical unsigned JSON plus signature field. */
    public static String toJson(ReleaseManifest manifest) {
        ManifestValidator.validate(manifest);
        String unsigned = CanonicalManifest.unsignedJson(manifest);
        return unsigned.substring(0, unsigned.length() - 1)
                + ",\"signature\":"
                + CanonicalManifest.quote(manifest.signature())
                + "}";
    }
}
