package com.prabin.swarmedge.common;

/** Blueprint default sizes and TTLs. Overridable by configuration in later phases. */
public final class Defaults {

    public static final int PROTOCOL_VERSION = 1;
    public static final int ASSET_ID_LENGTH = 32;
    public static final int PEER_ID_LENGTH = 16;

    public static final long CHUNK_SIZE_BYTES = 4L * 1024 * 1024;
    public static final int BLOCK_SIZE_BYTES = 256 * 1024;
    public static final int MAX_BLOCK_SIZE_BYTES = 512 * 1024;

    public static final int PEER_TTL_SECONDS = 45;
    public static final int HEARTBEAT_SECONDS = 15;
    public static final int TRACKER_CANDIDATE_LIMIT = 20;
    public static final int OUTSTANDING_REQUESTS_PER_PEER = 8;
    public static final int MAX_PEER_CONNECTIONS_PER_ASSET = 8;

    private Defaults() {
    }
}
