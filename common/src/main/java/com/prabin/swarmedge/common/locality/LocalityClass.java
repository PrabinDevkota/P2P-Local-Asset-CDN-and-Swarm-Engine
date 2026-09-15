package com.prabin.swarmedge.common.locality;

/**
 * How close two peers are, coarsely (blueprint P6-01, §8.2).
 *
 * <p>Declared best-first, so the natural enum order is also the preference order and
 * {@link #compareTo} ranks candidates without a comparator. The scores are the
 * blueprint's suggested engineering defaults and are not claimed optimal; the gap
 * between them is what matters, and sensitivity-testing it is Phase 10 work.
 *
 * <p>The tracker and the peer agent share this enum on purpose. When ranking lived in
 * the tracker alone and the peer re-derived closeness for itself, the two could disagree
 * about the same pair of peers — and did: the tracker checked site before group, which
 * made a peer across the building rank equal to one on the same switch.
 */
public enum LocalityClass {

    /** Same site and same network group: the same bandwidth and fault domain. */
    SAME_NETWORK_GROUP(1.0),

    /** Same site, different group: still local traffic, but across more of the network. */
    SAME_SITE(0.7),

    /** A different site, which normally means the WAN link this project exists to spare. */
    REMOTE_SITE(0.2);

    private final double score;

    LocalityClass(double score) {
        this.score = score;
    }

    /** The §8.2 {@code localityScore} term, in 0..1. */
    public double score() {
        return score;
    }

    /** True when traffic to this peer is expected to stay inside one site. */
    public boolean isLocal() {
        return this != REMOTE_SITE;
    }
}
