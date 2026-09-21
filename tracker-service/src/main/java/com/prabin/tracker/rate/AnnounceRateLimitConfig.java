package com.prabin.tracker.rate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Production uses Redis so the cap is shared across tracker instances. Tests that
 * do not start Redis can supply {@link AnnounceRateLimiter.Memory} instead.
 */
@Configuration
public class AnnounceRateLimitConfig {

    @Bean
    public AnnounceRateLimiter announceRateLimiter(
            StringRedisTemplate redis,
            @Value("${swarmedge.tracker.announce-rate-max:" + AnnounceRateLimiter.DEFAULT_MAX_PER_WINDOW + "}")
            int maxPerWindow,
            @Value("${swarmedge.tracker.announce-rate-window-seconds:1}") long windowSeconds) {
        return new AnnounceRateLimiter.Redis(redis, maxPerWindow, Duration.ofSeconds(windowSeconds));
    }
}
