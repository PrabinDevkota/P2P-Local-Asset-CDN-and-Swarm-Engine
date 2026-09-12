package com.prabin.tracker.api;

import com.jayway.jsonpath.JsonPath;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.tracker.auth.PeerTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({TrackerExceptionHandler.class, AuthControllerTest.FixedTokens.class})
class AuthControllerTest {

    private static final Instant T0 = Instant.parse("2026-09-12T00:00:00Z");
    private static final String PEER = "1".repeat(32);
    private static final byte[] SECRET = "a-32-byte-or-longer-tracker-secret".getBytes(StandardCharsets.UTF_8);

    @Autowired
    MockMvc mvc;

    @Autowired
    PeerTokens tokens;

    @Test
    void issuesATokenTheTrackerWillAccept() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/peer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"peerId":"%s","siteId":"site-a","networkGroupId":"ng-1"}
                                """.formatted(PEER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttlSeconds").value(300))
                .andExpect(jsonPath("$.expiresAt").value(T0.plusSeconds(300).toString()))
                .andReturn();

        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        PeerTokens.Claims claims = tokens.verifyFor(token, PeerId.fromHex(PEER), "site-a", "ng-1");
        assertThat(claims.expiresAt()).isEqualTo(T0.plusSeconds(300));
    }

    @Test
    void rejectsAMalformedPeerId() throws Exception {
        mvc.perform(post("/api/v1/auth/peer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"peerId":"nope","siteId":"site-a","networkGroupId":"ng-1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("peerId must be 32 hex characters"));
    }

    @Test
    void rejectsAMissingLocality() throws Exception {
        mvc.perform(post("/api/v1/auth/peer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"peerId":"%s","networkGroupId":"ng-1"}
                                """.formatted(PEER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("siteId is required"));
    }

    @Test
    void rejectsAnInvalidLocalityLabel() throws Exception {
        mvc.perform(post("/api/v1/auth/peer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"peerId":"%s","siteId":"site a","networkGroupId":"ng-1"}
                                """.formatted(PEER)))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration
    static class FixedTokens {
        @Bean
        PeerTokens peerTokens() {
            return new PeerTokens(SECRET, PeerTokens.DEFAULT_TTL, Clock.fixed(T0, ZoneOffset.UTC));
        }
    }
}
