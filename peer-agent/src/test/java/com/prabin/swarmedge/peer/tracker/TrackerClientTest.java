package com.prabin.swarmedge.peer.tracker;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TrackerClientTest {

    private static final AssetId ASSET = AssetId.fromHex("aa".repeat(32));
    private static final PeerId SELF = PeerId.fromHex("bb".repeat(16));
    private static final PeerId OTHER = PeerId.fromHex("cc".repeat(16));

    @Test
    void announceSendsBudgetAndDiscoveryMapsUploadLoad() throws Exception {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/peers/announce", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "{\"observedIp\":\"127.0.0.1\",\"ttlSeconds\":45}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.createContext("/api/v1/assets/" + ASSET.toHex() + "/peers", exchange -> {
            byte[] body = ("""
                    {"assetId":"%s","peers":[{"peerId":"%s","ip":"10.0.0.8","port":9091,\
                    "siteId":"hq","networkGroupId":"floor-2","bitfield":{"bitCount":0,"bits":""},\
                    "capabilities":4,"uploadBudget":1000000,"uploadLoad":0.25}]}
                    """).formatted(ASSET.toHex(), OTHER.toHex()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            TrackerClient client = new TrackerClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            client.announce("secret-token", new TrackerClient.Announce(
                    ASSET.toHex(), SELF.toHex(), 9091, "hq", "floor-2",
                    new TrackerClient.Announce.Bitfield(0, ""), 4, 1_000_000L, 0.25));

            assertThat(seenAuth.get()).isEqualTo("Bearer secret-token");
            assertThat(seenBody.get()).contains("\"uploadBudget\":1000000");
            assertThat(seenBody.get()).contains("\"capabilities\":4");
            assertThat(seenBody.get()).doesNotContain("secret-token");

            TrackerClient.DiscoveredPeer peer = client.peers(ASSET, SELF, 20).getFirst();
            assertThat(peer.peerId()).isEqualTo(OTHER);
            assertThat(peer.capabilities()).isEqualTo(4);
            assertThat(peer.uploadBudgetBytesPerSecond()).isEqualTo(1_000_000L);
            assertThat(peer.candidate().advertisedUploadLoad()).hasValue(0.25);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theAnnounceLoopRepeatsUntilClosed() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/peers/announce", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            TrackerClient client = new TrackerClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            TrackerClient.Announce body = new TrackerClient.Announce(
                    ASSET.toHex(), SELF.toHex(), 9091, "hq", "floor-2",
                    new TrackerClient.Announce.Bitfield(0, ""), 0, 0L, null);
            try (AnnounceLoop loop = new AnnounceLoop(client, "tok", () -> body, Duration.ofMillis(30))) {
                loop.start();
                long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (hits.get() < 2 && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
            }
            assertThat(hits.get()).isGreaterThanOrEqualTo(2);
        } finally {
            server.stop(0);
        }
    }
}
