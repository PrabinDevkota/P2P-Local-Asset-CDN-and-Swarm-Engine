package com.prabin.swarm_node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the peer data plane (future peer-agent).
 *
 * First principles:
 * - Tracker tells us who might help; the signed manifest decides what is valid.
 * - This process speaks Netty protocol v1 and later schedules blocks (not whole files).
 * - Role is policy (LEECHER/SEEDER/EDGE), not a separate binary.
 *
 * The session layer itself lives in {@code peer.net} and {@code peer.session}. This entry point
 * cannot drive it yet: a seeder has to be handed a manifest that was verified against a trusted
 * key, and that key handling is a later phase.
 */
public final class SwarmNode {

    private static final Logger log = LoggerFactory.getLogger(SwarmNode.class);

    private SwarmNode() {
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("NETTY_PORT", "9091"));
        String trackerUrl = System.getenv().getOrDefault("TRACKER_URL", "http://localhost:8080");
        String role = System.getenv().getOrDefault("PEER_ROLE", "LEECHER");
        String siteId = System.getenv().getOrDefault("SITE_ID", "site-local");
        String networkGroupId = System.getenv().getOrDefault("NETWORK_GROUP_ID", "ng-local");

        log.info(
                "swarm-node scaffold starting (port={}, tracker={}, role={}, siteId={}, networkGroupId={})",
                port,
                trackerUrl,
                role,
                siteId,
                networkGroupId
        );
        log.info("scaffold only: use SeederServer / LeecherClient directly until the CLI lands");
    }
}
