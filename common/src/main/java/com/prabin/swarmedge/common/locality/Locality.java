package com.prabin.swarmedge.common.locality;

import java.util.Objects;

/**
 * Where a peer sits in the network, as administrative labels (blueprint P6-01, §8.2).
 *
 * <p>Two labels, deliberately. A <em>site</em> is the broad place — a campus, a branch,
 * an office — and a <em>network group</em> is the narrower fault and bandwidth domain
 * inside it: a VLAN, a switch stack, a floor. A network group belongs to exactly one
 * site, so "same group" always implies "same site" and is the stronger statement.
 *
 * <p>Neither label is derived from an address. The blueprint is explicit that locality
 * must not be a {@code /24} guess, and the reason is not neatness: in a real enterprise
 * network, one subnet can span a WAN link while two subnets sit on the same switch.
 * Which peers are genuinely close is something the network's owner knows and a netmask
 * does not, so this is configuration and it arrives with the peer.
 *
 * <p>A peer never earns a better locality class by claiming one. These labels reach the
 * tracker inside an issued peer token, which is what stops a remote peer from announcing
 * itself into someone else's network group.
 *
 * @param siteId         the broad location label; opaque and compared exactly
 * @param networkGroupId the narrower label within that site
 */
public record Locality(String siteId, String networkGroupId) {

    public Locality {
        siteId = requireLabel(siteId, "siteId");
        networkGroupId = requireLabel(networkGroupId, "networkGroupId");
    }

    /**
     * How close {@code other} is to this peer.
     *
     * <p>The group check comes first because it is the finer one. Testing the site first
     * would let a peer two VLANs away tie with a peer on the same switch, which is the
     * distinction the whole phase exists to make.
     */
    public LocalityClass classify(Locality other) {
        Objects.requireNonNull(other, "other");
        if (siteId.equals(other.siteId)) {
            return networkGroupId.equals(other.networkGroupId)
                    ? LocalityClass.SAME_NETWORK_GROUP
                    : LocalityClass.SAME_SITE;
        }
        // A matching group label in a different site is a coincidence of naming, not
        // proximity: "floor-2" exists in every building.
        return LocalityClass.REMOTE_SITE;
    }

    /** The §8.2 locality term, already in the 0..1 range the score expects. */
    public double scoreOf(Locality other) {
        return classify(other).score();
    }

    @Override
    public String toString() {
        return siteId + "/" + networkGroupId;
    }

    private static String requireLabel(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
