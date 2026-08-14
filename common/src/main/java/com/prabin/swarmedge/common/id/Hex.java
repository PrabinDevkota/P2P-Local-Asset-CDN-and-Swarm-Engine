package com.prabin.swarmedge.common.id;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public final class Hex {

    private static final HexFormat FORMAT = HexFormat.of().withLowerCase();

    private Hex() {
    }

    public static String toLowerHex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return FORMAT.formatHex(bytes);
    }

    public static byte[] fromHex(String hex) {
        Objects.requireNonNull(hex, "hex");
        String normalized = hex.trim().toLowerCase(Locale.ROOT);
        if ((normalized.length() & 1) != 0) {
            throw new IllegalArgumentException("hex length must be even");
        }
        if (!normalized.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("hex contains non-hex characters");
        }
        return FORMAT.parseHex(normalized);
    }
}
