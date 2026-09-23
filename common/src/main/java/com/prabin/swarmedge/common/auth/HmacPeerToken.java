package com.prabin.swarmedge.common.auth;

import com.prabin.swarmedge.common.id.PeerId;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * HMAC peer token shared by the tracker (issuer) and the seeder (HELLO check).
 *
 * <p>This is the research stand-in for mTLS. A token binds a peer id to a site
 * policy until {@code expiresAt}. It never authorizes chunk bytes.
 */
public final class HmacPeerToken {

    public static final String VERSION = "v1";
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private static final int MAX_LABEL_LENGTH = 64;
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    public HmacPeerToken(byte[] secret, Duration ttl, Clock clock) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length < 32) {
            throw new IllegalArgumentException("token secret must be at least 32 bytes");
        }
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.secret = secret.clone();
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Duration ttl() {
        return ttl;
    }

    public Issued issue(PeerId peerId, String siteId, String networkGroupId) {
        Objects.requireNonNull(peerId, "peerId");
        String site = requireLabel(siteId, "siteId");
        String group = requireLabel(networkGroupId, "networkGroupId");
        Instant expiresAt = clock.instant().plus(ttl).truncatedTo(ChronoUnit.SECONDS);
        String payload = VERSION + "|" + peerId.toHex() + "|" + site + "|" + group + "|" + expiresAt.getEpochSecond();
        String token = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + ENCODER.encodeToString(mac(payload));
        return new Issued(token, expiresAt, ttl.toSeconds());
    }

    public Claims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("token is required");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1 || token.indexOf('.', dot + 1) >= 0) {
            throw new InvalidTokenException("token is malformed");
        }
        String payload;
        byte[] presented;
        try {
            payload = new String(DECODER.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            presented = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("token is malformed");
        }
        if (!MessageDigest.isEqual(mac(payload), presented)) {
            throw new InvalidTokenException("token signature does not match");
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 5 || !VERSION.equals(fields[0])) {
            throw new InvalidTokenException("token is malformed");
        }
        PeerId peerId;
        long expiresAtSeconds;
        try {
            peerId = PeerId.fromHex(fields[1]);
            expiresAtSeconds = Long.parseLong(fields[4]);
        } catch (RuntimeException e) {
            throw new InvalidTokenException("token is malformed");
        }
        Instant expiresAt = Instant.ofEpochSecond(expiresAtSeconds);
        if (!clock.instant().isBefore(expiresAt)) {
            throw new InvalidTokenException("token has expired");
        }
        return new Claims(peerId, fields[2], fields[3], expiresAt);
    }

    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    private static String requireLabel(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String label = value.trim();
        if (label.length() > MAX_LABEL_LENGTH || !LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException(name + " is not a valid label");
        }
        return label;
    }

    public record Issued(String token, Instant expiresAt, long ttlSeconds) {
    }

    public record Claims(PeerId peerId, String siteId, String networkGroupId, Instant expiresAt) {
    }

    public static final class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
