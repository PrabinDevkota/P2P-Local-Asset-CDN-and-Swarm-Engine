package com.prabin.tracker.api;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.tracker.auth.PeerTokens;
import com.prabin.tracker.rate.AnnounceRateLimiter;
import com.prabin.tracker.store.PeerDirectory;
import com.prabin.tracker.store.PeerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PeerController.class)
@Import({TrackerExceptionHandler.class, PeerControllerRateLimitTest.MemoryPeerDirectory.class,
        PeerControllerRateLimitTest.FixedTokens.class, PeerControllerRateLimitTest.TightLimiter.class})
class PeerControllerRateLimitTest {

    private static final String ASSET = "a".repeat(64);
    private static final String SELF = "1".repeat(32);
    private static final String OTHER = "2".repeat(32);
    private static final byte[] SECRET = "a-32-byte-or-longer-tracker-secret".getBytes(StandardCharsets.UTF_8);

    @Autowired
    MockMvc mvc;

    @Autowired
    PeerTokens tokens;

    @Test
    void aBurstFromOnePeerIs429AndDoesNotBlockAnotherPeer() throws Exception {
        announce(SELF).andExpect(status().isOk());
        announce(SELF).andExpect(status().isOk());
        announce(SELF)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("announce rate limit exceeded for peer " + SELF));

        announce(OTHER).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions announce(String peerId) throws Exception {
        return mvc.perform(post("/api/v1/peers/announce")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer "
                        + tokens.issue(PeerId.fromHex(peerId), "site-a", "ng-1").token())
                .content("""
                        {"assetId":"%s","peerId":"%s","port":9091,"siteId":"site-a","networkGroupId":"ng-1",\
                        "bitfield":{"bitCount":0,"bits":""},"ip":"1.2.3.4"}
                        """.formatted(ASSET, peerId)));
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

    @TestConfiguration
    static class FixedTokens {
        @Bean
        PeerTokens peerTokens() {
            return new PeerTokens(SECRET, PeerTokens.DEFAULT_TTL, Clock.systemUTC());
        }
    }

    @TestConfiguration
    static class TightLimiter {
        @Bean
        AnnounceRateLimiter announceRateLimiter() {
            return new AnnounceRateLimiter.Memory(2, Duration.ofSeconds(30));
        }
    }
}
