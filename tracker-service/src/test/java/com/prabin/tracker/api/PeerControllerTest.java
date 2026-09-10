package com.prabin.tracker.api;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.tracker.store.PeerDirectory;
import com.prabin.tracker.store.PeerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PeerController.class)
@Import({TrackerExceptionHandler.class, PeerControllerTest.MemoryPeerDirectory.class})
class PeerControllerTest {

    private static final String ASSET = "a".repeat(64);
    private static final String SELF = "1".repeat(32);
    private static final String SAME_SITE = "2".repeat(32);
    private static final String SAME_GROUP = "3".repeat(32);
    private static final String REMOTE = "4".repeat(32);

    @Autowired
    MockMvc mvc;

    @Test
    void announceStoresObservedIpAndRejectsBadPayloads() throws Exception {
        mvc.perform(post("/api/v1/peers/announce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(announce(SELF, "site-a", "ng-1"))
                        .with(request -> {
                            request.setRemoteAddr("10.9.8.7");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observedIp").value("10.9.8.7"))
                .andExpect(jsonPath("$.ttlSeconds").value(45));

        mvc.perform(post("/api/v1/peers/announce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(announce(SELF, "site-a", "ng-1").replace("9091", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        mvc.perform(post("/api/v1/peers/announce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsPeersRankedBySiteThenGroupExcludingSelf() throws Exception {
        postPeer(SELF, "site-a", "ng-1");
        postPeer(REMOTE, "site-c", "ng-9");
        postPeer(SAME_GROUP, "site-b", "ng-1");
        postPeer(SAME_SITE, "site-a", "ng-2");

        mvc.perform(get("/api/v1/assets/{assetId}/peers", ASSET)
                        .param("peerId", SELF)
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(ASSET))
                .andExpect(jsonPath("$.peers.length()").value(3))
                .andExpect(jsonPath("$.peers[0].peerId").value(SAME_SITE))
                .andExpect(jsonPath("$.peers[1].peerId").value(SAME_GROUP))
                .andExpect(jsonPath("$.peers[2].peerId").value(REMOTE));
    }

    private void postPeer(String peerId, String site, String group) throws Exception {
        mvc.perform(post("/api/v1/peers/announce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(announce(peerId, site, group)))
                .andExpect(status().isOk());
    }

    private static String announce(String peerId, String site, String group) {
        return """
                {"assetId":"%s","peerId":"%s","port":9091,"siteId":"%s","networkGroupId":"%s",\
                "bitfield":{"bitCount":0,"bits":""},"ip":"1.2.3.4"}
                """.formatted(ASSET, peerId, site, group);
    }

    @Component
    static final class MemoryPeerDirectory implements PeerDirectory {
        private final Map<String, Map<String, PeerRecord>> peers = new ConcurrentHashMap<>();

        @Override
        public void save(AssetId assetId, PeerRecord peer) {
            peers.computeIfAbsent(assetId.toHex(), key -> new ConcurrentHashMap<>())
                    .put(peer.peerId().toHex(), peer);
        }

        @Override
        public List<PeerRecord> list(AssetId assetId) {
            return List.copyOf(peers.getOrDefault(assetId.toHex(), Map.of()).values());
        }
    }
}
