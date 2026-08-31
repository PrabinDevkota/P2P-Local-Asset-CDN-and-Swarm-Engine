package com.prabin.swarmedge.manifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/**
 * Generate and PEM-encode Ed25519 keys for the manifest CLI.
 * Private keys are PKCS#8; public keys are X.509 SubjectPublicKeyInfo.
 */
public final class Ed25519Keys {

    private static final String PRIVATE_TYPE = "PRIVATE KEY";
    private static final String PUBLIC_TYPE = "PUBLIC KEY";
    private static final Base64.Encoder PEM_BODY = Base64.getMimeEncoder(64, new byte[] {'\n'});

    private Ed25519Keys() {
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 not available", e);
        }
    }

    public static void writePrivateKey(Path path, PrivateKey key) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(key, "key");
        Files.writeString(path, toPem(PRIVATE_TYPE, key.getEncoded()), StandardCharsets.US_ASCII);
    }

    public static void writePublicKey(Path path, PublicKey key) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(key, "key");
        Files.writeString(path, toPem(PUBLIC_TYPE, key.getEncoded()), StandardCharsets.US_ASCII);
    }

    public static PrivateKey readPrivateKey(Path path) throws IOException {
        return decodePrivate(fromPem(Files.readString(path, StandardCharsets.US_ASCII), PRIVATE_TYPE));
    }

    public static PublicKey readPublicKey(Path path) throws IOException {
        return decodePublic(fromPem(Files.readString(path, StandardCharsets.US_ASCII), PUBLIC_TYPE));
    }

    static String toPem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + PEM_BODY.encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    static byte[] fromPem(String pem, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int start = pem.indexOf(begin);
        int stop = pem.indexOf(end);
        if (start < 0 || stop < 0 || stop <= start) {
            throw new IllegalArgumentException("PEM must contain " + begin);
        }
        String body = pem.substring(start + begin.length(), stop).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("PEM body is not valid Base64", e);
        }
    }

    private static PrivateKey decodePrivate(byte[] der) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not an Ed25519 private key", e);
        }
    }

    private static PublicKey decodePublic(byte[] der) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not an Ed25519 public key", e);
        }
    }
}
