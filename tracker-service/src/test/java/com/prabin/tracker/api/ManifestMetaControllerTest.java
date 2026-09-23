package com.prabin.tracker.api;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.tracker.store.ManifestMetaStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ManifestMetaController.class)
@Import({TrackerExceptionHandler.class, ManifestMetaControllerTest.MemoryMeta.class})
class ManifestMetaControllerTest {

    private static final String ASSET = "a".repeat(64);

    @Autowired
    MockMvc mvc;

    @Test
    void putThenGetReturnsTheIndexAndDoesNotRequireTheManifestBytes() throws Exception {
        mvc.perform(get("/api/v1/assets/{assetId}/manifest", ASSET))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/v1/assets/{assetId}/manifest", ASSET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"game-x","version":"1.4.0",\
                                "manifestUrl":"https://origin.example/manifests/game-x.json",\
                                "publishedAt":"2026-09-23T00:00:00Z"}
                                """))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/assets/{assetId}/manifest", ASSET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value("game-x"))
                .andExpect(jsonPath("$.version").value("1.4.0"))
                .andExpect(jsonPath("$.manifestUrl").value("https://origin.example/manifests/game-x.json"));
    }

    @Test
    void aNonHttpUrlIsRejected() throws Exception {
        mvc.perform(put("/api/v1/assets/{assetId}/manifest", ASSET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"game-x","version":"1.4.0",\
                                "manifestUrl":"file:///tmp/release.json",\
                                "publishedAt":"2026-09-23T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Component
    static final class MemoryMeta implements ManifestMetaStore {
        private final ConcurrentHashMap<String, ManifestMeta> rows = new ConcurrentHashMap<>();

        @Override
        public void save(AssetId assetId, ManifestMeta meta) {
            rows.put(assetId.toHex(), meta);
        }

        @Override
        public Optional<ManifestMeta> find(AssetId assetId) {
            return Optional.ofNullable(rows.get(assetId.toHex()));
        }
    }
}
