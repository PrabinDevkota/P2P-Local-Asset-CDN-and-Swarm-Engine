package com.prabin.swarmedge.peer.tracker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.common.locality.Locality;
import com.prabin.swarmedge.peer.laps.PeerSelector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Tracker phone-book client: announce this peer and read ranked candidates.
 * The token is sent as a bearer header and is never logged.
 */
public final class TrackerClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI base;

    public TrackerClient(URI base) {
        this(base, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public TrackerClient(URI base, HttpClient http) {
        this.base = Objects.requireNonNull(base, "base");
        this.http = Objects.requireNonNull(http, "http");
    }

    public void announce(String bearerToken, Announce body) throws IOException, InterruptedException {
        Objects.requireNonNull(bearerToken, "bearerToken");
        Objects.requireNonNull(body, "body");
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/api/v1/peers/announce"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("announce failed: HTTP " + response.statusCode());
        }
    }

    public List<DiscoveredPeer> peers(AssetId assetId, PeerId self, int limit)
            throws IOException, InterruptedException {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(self, "self");
        URI uri = base.resolve("/api/v1/assets/" + assetId.toHex() + "/peers?peerId="
                + self.toHex() + "&limit=" + limit);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("discovery failed: HTTP " + response.statusCode());
        }
        CandidateList list = JSON.readValue(response.body(), CandidateList.class);
        List<DiscoveredPeer> peers = new ArrayList<>();
        if (list.peers != null) {
            for (WirePeer peer : list.peers) {
                peers.add(peer.toPeer());
            }
        }
        return List.copyOf(peers);
    }

    public record Announce(
            String assetId,
            String peerId,
            int port,
            String siteId,
            String networkGroupId,
            Bitfield bitfield,
            int capabilities,
            long uploadBudget,
            Double uploadLoad
    ) {
        public record Bitfield(int bitCount, String bits) {
        }
    }

    public record DiscoveredPeer(
            PeerId peerId,
            String ip,
            int port,
            Locality locality,
            int capabilities,
            long uploadBudgetBytesPerSecond,
            OptionalDouble uploadLoad
    ) {
        public PeerSelector.Candidate candidate() {
            PeerSelector.Candidate base = PeerSelector.Candidate.of(
                    new java.net.InetSocketAddress(ip, port), peerId, locality);
            return uploadLoad.isPresent() ? base.withAdvertisedUploadLoad(uploadLoad.getAsDouble()) : base;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class CandidateList {
        public List<WirePeer> peers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class WirePeer {
        public String peerId;
        public String ip;
        public int port;
        public String siteId;
        public String networkGroupId;
        public int capabilities;
        public long uploadBudget;
        public Double uploadLoad;

        DiscoveredPeer toPeer() {
            OptionalDouble load = uploadLoad == null ? OptionalDouble.empty() : OptionalDouble.of(uploadLoad);
            return new DiscoveredPeer(
                    PeerId.fromHex(peerId),
                    ip,
                    port,
                    new Locality(siteId, networkGroupId),
                    capabilities,
                    uploadBudget,
                    load);
        }
    }
}
