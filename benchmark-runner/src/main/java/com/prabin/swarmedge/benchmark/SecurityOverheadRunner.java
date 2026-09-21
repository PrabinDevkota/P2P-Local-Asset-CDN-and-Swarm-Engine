package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.common.id.Hex;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.Ed25519Keys;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ManifestSigner;
import com.prabin.swarmedge.manifest.ManifestVerifier;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * P9-04: time SHA-256, Ed25519 sign/verify, and HMAC-SHA256 on a fixed payload.
 *
 * <p>The numbers are observations. This class does not compare them to an SLO,
 * does not fold them into a LAPS or B0–B6 result, and does not claim that one
 * operation is cheap.
 */
public final class SecurityOverheadRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityOverheadRunner.class);
    private static final byte[] HMAC_KEY = "a-32-byte-or-longer-overhead-key!".getBytes(StandardCharsets.UTF_8);

    private final SecurityOverheadConfig config;
    private final Path workDir;

    public SecurityOverheadRunner(SecurityOverheadConfig config, Path workDir) {
        this.config = Objects.requireNonNull(config, "config");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
    }

    public Summary run() throws Exception {
        Files.createDirectories(workDir);
        byte[] payload = deterministicBytes(config.payloadBytes(), config.seed());
        Path file = workDir.resolve("payload.bin");
        Files.write(file, payload);

        int chunkSize = Math.min(config.payloadBytes(), 4096);
        List<ChunkEntry> chunks = new FileChunker(chunkSize).chunk(file);
        ReleaseManifest unsigned = ReleaseManifestFactory.unsigned(
                "overhead", "1.0.0", file, chunkSize, chunks,
                "2026-09-21T00:00:00Z", "2026-12-21T00:00:00Z", 1, "overhead-key");
        KeyPair keys = Ed25519Keys.generate();

        List<Long> hashNanos = new ArrayList<>(config.repetitions());
        List<Long> signNanos = new ArrayList<>(config.repetitions());
        List<Long> verifyNanos = new ArrayList<>(config.repetitions());
        List<Long> hmacNanos = new ArrayList<>(config.repetitions());
        String sha256Hex = sha256Hex(payload);
        ReleaseManifest lastSigned = null;

        for (int i = 0; i < config.repetitions(); i++) {
            hashNanos.add(time(() -> sha256Hex(payload)));
            Timed<ReleaseManifest> signed = timeValue(() -> ManifestSigner.sign(unsigned, keys.getPrivate()));
            signNanos.add(signed.nanos());
            lastSigned = signed.value();
            PublicKey publicKey = keys.getPublic();
            ReleaseManifest toVerify = lastSigned;
            verifyNanos.add(time(() -> ManifestVerifier.verify(toVerify, publicKey)));
            hmacNanos.add(time(() -> hmac(payload)));
        }

        ManifestVerifier.verify(lastSigned, keys.getPublic());
        log.info("{} hashSamples={} signSamples={} verifySamples={} hmacSamples={} payloadBytes={}",
                config.scenarioId(), hashNanos.size(), signNanos.size(), verifyNanos.size(), hmacNanos.size(),
                config.payloadBytes());
        return new Summary(config.scenarioId(), config.baseline(), config.seed(), config.payloadBytes(),
                sha256Hex, new Op("sha256", hashNanos), new Op("ed25519-sign", signNanos),
                new Op("ed25519-verify", verifyNanos), new Op("hmac-sha256", hmacNanos));
    }

    private static long time(Runnable action) {
        long started = System.nanoTime();
        action.run();
        return System.nanoTime() - started;
    }

    private static <T> Timed<T> timeValue(ThrowingSupplier<T> action) throws Exception {
        long started = System.nanoTime();
        T value = action.get();
        return new Timed<>(value, System.nanoTime() - started);
    }

    private static String sha256Hex(byte[] payload) {
        return Hex.toLowerHex(sha256().digest(payload));
    }

    private static byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] deterministicBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record Timed<T>(T value, long nanos) {
    }

    public record Op(String name, List<Long> nanos) {
        public Op {
            Objects.requireNonNull(name, "name");
            nanos = List.copyOf(Objects.requireNonNull(nanos, "nanos"));
        }

        public long min() {
            return nanos.stream().mapToLong(Long::longValue).min().orElse(0);
        }

        public long max() {
            return nanos.stream().mapToLong(Long::longValue).max().orElse(0);
        }

        public long total() {
            return nanos.stream().mapToLong(Long::longValue).sum();
        }
    }

    public record Summary(
            String scenarioId,
            String baseline,
            long seed,
            int payloadBytes,
            String sha256Hex,
            Op hash,
            Op sign,
            Op verify,
            Op hmac
    ) {
    }
}
