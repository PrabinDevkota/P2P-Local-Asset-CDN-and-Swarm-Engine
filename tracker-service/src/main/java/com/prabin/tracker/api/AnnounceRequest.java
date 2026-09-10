package com.prabin.tracker.api;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.Hex;
import com.prabin.swarmedge.common.id.PeerId;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Peer heartbeat payload. Tracker stores locality and bitfield only — never file bytes.
 * Observed IP is taken from the connection, not from this body.
 */
public record AnnounceRequest(
        String assetId,
        String peerId,
        int port,
        String siteId,
        String networkGroupId,
        Bitfield bitfield
) {

    public static final int MAX_LABEL_LENGTH = 64;
    public static final int MAX_BIT_COUNT = 1_048_576;
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9._-]+");

    public record Bitfield(int bitCount, String bits) {
    }

    public Validated validate() {
        AssetId asset = parseAssetId(assetId);
        PeerId peer = parsePeerId(peerId);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be 1-65535");
        }
        String site = requireLabel(siteId, "siteId");
        String group = requireLabel(networkGroupId, "networkGroupId");
        if (bitfield == null) {
            throw new IllegalArgumentException("bitfield is required");
        }
        int bitCount = bitfield.bitCount();
        if (bitCount < 0 || bitCount > MAX_BIT_COUNT) {
            throw new IllegalArgumentException("bitCount must be 0-" + MAX_BIT_COUNT);
        }
        byte[] bits = parseBits(bitfield.bits(), bitCount);
        return new Validated(asset, peer, port, site, group, bitCount, bits);
    }

    public record Validated(
            AssetId assetId,
            PeerId peerId,
            int port,
            String siteId,
            String networkGroupId,
            int bitCount,
            byte[] bits
    ) {
        public Validated {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(peerId, "peerId");
            Objects.requireNonNull(siteId, "siteId");
            Objects.requireNonNull(networkGroupId, "networkGroupId");
            Objects.requireNonNull(bits, "bits");
        }
    }

    private static AssetId parseAssetId(String hex) {
        try {
            return AssetId.fromHex(requireText(hex, "assetId"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("assetId must be 64 hex characters");
        }
    }

    private static PeerId parsePeerId(String hex) {
        try {
            return PeerId.fromHex(requireText(hex, "peerId"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("peerId must be 32 hex characters");
        }
    }

    private static byte[] parseBits(String hex, int bitCount) {
        int expectedBytes = bitfieldByteLength(bitCount);
        String normalized = hex == null ? "" : hex.trim().toLowerCase(Locale.ROOT);
        if (expectedBytes == 0) {
            if (!normalized.isEmpty()) {
                throw new IllegalArgumentException("bits must be empty when bitCount is 0");
            }
            return new byte[0];
        }
        byte[] bits;
        try {
            bits = Hex.fromHex(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bits must be lowercase hex");
        }
        if (bits.length != expectedBytes) {
            throw new IllegalArgumentException(
                    "bits length must be " + (expectedBytes * 2) + " hex characters");
        }
        return bits;
    }

    static int bitfieldByteLength(int bitCount) {
        return (bitCount + 7) / 8;
    }

    private static String requireLabel(String value, String name) {
        String label = requireText(value, name);
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException(name + " is too long");
        }
        if (!LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException(name + " has invalid characters");
        }
        return label;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
