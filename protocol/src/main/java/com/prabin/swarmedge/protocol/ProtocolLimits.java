package com.prabin.swarmedge.protocol;

import com.prabin.swarmedge.common.Defaults;

public final class ProtocolLimits {

    public static final int HEADER_AFTER_LENGTH = 12;
    public static final int MAX_FRAME_LENGTH = 1_048_576;
    public static final int MAX_TOKEN_BYTES = 4096;
    public static final int MAX_ERROR_UTF8_BYTES = 256;

    private ProtocolLimits() {
    }

    public static int defaultMaxBlockSize() {
        return Defaults.BLOCK_SIZE_BYTES;
    }

    public static int absoluteMaxBlockSize() {
        return Defaults.MAX_BLOCK_SIZE_BYTES;
    }
}
