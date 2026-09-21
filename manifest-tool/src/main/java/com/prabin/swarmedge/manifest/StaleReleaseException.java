package com.prabin.swarmedge.manifest;

/**
 * A signed release that must not be used: it has expired, or its sequence is behind
 * the highest one this process has already accepted for the same product.
 *
 * <p>This is not a signature failure. Crypto already passed; freshness did not.
 */
public final class StaleReleaseException extends RuntimeException {

    public enum Reason {
        EXPIRED,
        ROLLBACK
    }

    private final Reason reason;
    private final String productId;
    private final long sequence;

    public StaleReleaseException(Reason reason, String productId, long sequence, String message) {
        super(message);
        this.reason = reason;
        this.productId = productId;
        this.sequence = sequence;
    }

    public Reason reason() {
        return reason;
    }

    public String productId() {
        return productId;
    }

    public long sequence() {
        return sequence;
    }
}
