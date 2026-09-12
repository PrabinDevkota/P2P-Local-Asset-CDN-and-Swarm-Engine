package com.prabin.tracker.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

/**
 * Wires the peer-token issuer. There is deliberately no default secret: a shared
 * constant in a repository is not a secret, and a tracker that silently accepts
 * everyone's tokens is worse than one that refuses to start with a warning.
 */
@Configuration
public class TrackerAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(TrackerAuthConfig.class);
    private static final int GENERATED_SECRET_BYTES = 32;

    @Bean
    public PeerTokens peerTokens(
            @Value("${swarmedge.tracker.token-secret:}") String configuredSecret,
            @Value("${swarmedge.tracker.token-ttl-seconds:300}") long ttlSeconds) {
        return new PeerTokens(secretBytes(configuredSecret), Duration.ofSeconds(ttlSeconds), Clock.systemUTC());
    }

    /** A per-boot random secret keeps development usable; every restart invalidates old tokens. */
    private static byte[] secretBytes(String configured) {
        if (configured == null || configured.isBlank()) {
            log.warn("swarmedge.tracker.token-secret is not set; generating a random one. "
                    + "Peer tokens will not survive a restart and will not work across instances.");
            byte[] generated = new byte[GENERATED_SECRET_BYTES];
            new SecureRandom().nextBytes(generated);
            return generated;
        }
        return configured.trim().getBytes(StandardCharsets.UTF_8);
    }
}
