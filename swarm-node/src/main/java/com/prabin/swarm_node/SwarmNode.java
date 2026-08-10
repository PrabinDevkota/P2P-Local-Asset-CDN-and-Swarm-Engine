package com.prabin.swarm_node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for a P2P peer (seeder or leecher).
 * Netty server/client, protocol, and transfer logic come in later phases.
 */
public final class SwarmNode {

    private static final Logger log = LoggerFactory.getLogger(SwarmNode.class);

    private SwarmNode() {
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("NETTY_PORT", "9091"));
        String trackerUrl = System.getenv().getOrDefault("TRACKER_URL", "http://localhost:8080");
        boolean hasFile = Boolean.parseBoolean(System.getenv().getOrDefault("HAS_FILE", "false"));

        log.info("swarm-node scaffold starting (port={}, tracker={}, hasFile={})", port, trackerUrl, hasFile);
        log.info("Next: Netty bootstrap, binary framing, handshake/bitfield (Phase 4)");
    }
}
