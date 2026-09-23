package com.prabin.tracker.api;

import com.prabin.swarmedge.common.Defaults;
import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.Hex;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.tracker.auth.PeerTokens;
import com.prabin.tracker.rank.LocalityRanker;
import com.prabin.tracker.rate.AnnounceRateLimiter;
import com.prabin.tracker.store.PeerDirectory;
import com.prabin.tracker.store.PeerRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Tracker phone book. Observed connection IP is stored; advertised IPs in the
 * body are ignored because this API never carries file bytes or content trust.
 */
@RestController
public final class PeerController {

    private static final String BEARER = "Bearer ";

    private final PeerDirectory directory;
    private final PeerTokens tokens;
    private final AnnounceRateLimiter announceLimiter;

    public PeerController(PeerDirectory directory, PeerTokens tokens, AnnounceRateLimiter announceLimiter) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.announceLimiter = Objects.requireNonNull(announceLimiter, "announceLimiter");
    }

    @PostMapping("/api/v1/peers/announce")
    public AnnounceAck announce(@RequestBody AnnounceRequest body, HttpServletRequest request) {
        Objects.requireNonNull(body, "body");
        AnnounceRequest.Validated v = body.validate();
        // Identity and locality come from the token, never from the body alone.
        tokens.verifyFor(bearerToken(request), v.peerId(), v.siteId(), v.networkGroupId());
        if (!announceLimiter.tryAcquire(v.peerId())) {
            throw new AnnounceRateLimiter.LimitedException(v.peerId());
        }
        String ip = observedIp(request);
        directory.save(
                v.assetId(),
                new PeerRecord(v.peerId(), ip, v.port(), v.siteId(), v.networkGroupId(), v.bitCount(), v.bits(),
                        v.capabilities(), v.uploadBudgetBytesPerSecond(), v.uploadLoad()));
        return new AnnounceAck(ip, Defaults.PEER_TTL_SECONDS);
    }

    static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            throw new PeerTokens.InvalidTokenException("a bearer peer token is required");
        }
        return header.substring(BEARER.length()).trim();
    }

    @GetMapping("/api/v1/assets/{assetId}/peers")
    public CandidateList list(
            @PathVariable("assetId") String assetId,
            @RequestParam("peerId") String peerId,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        AssetId asset = parseAssetId(assetId);
        PeerId self = parsePeerId(peerId);
        int cap = capLimit(limit);
        List<PeerRecord> all = directory.list(asset);
        String siteId = "";
        String networkGroupId = "";
        for (PeerRecord peer : all) {
            if (self.equals(peer.peerId())) {
                siteId = peer.siteId();
                networkGroupId = peer.networkGroupId();
                break;
            }
        }
        List<PeerRecord> ranked = LocalityRanker.rank(all, siteId, networkGroupId, self, cap);
        List<Candidate> candidates = ranked.stream().map(Candidate::from).toList();
        return new CandidateList(asset.toHex(), candidates);
    }

    static String observedIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("observed IP is required");
        }
        return ip.trim();
    }

    static int capLimit(Integer limit) {
        if (limit == null) {
            return Defaults.TRACKER_CANDIDATE_LIMIT;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return Math.min(limit, Defaults.TRACKER_CANDIDATE_LIMIT);
    }

    private static AssetId parseAssetId(String hex) {
        try {
            return AssetId.fromHex(hex);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("assetId must be 64 hex characters");
        }
    }

    private static PeerId parsePeerId(String hex) {
        try {
            return PeerId.fromHex(hex);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("peerId must be 32 hex characters");
        }
    }

    public record AnnounceAck(String observedIp, int ttlSeconds) {
    }

    public record CandidateList(String assetId, List<Candidate> peers) {
    }

    public record Candidate(
            String peerId,
            String ip,
            int port,
            String siteId,
            String networkGroupId,
            AnnounceRequest.Bitfield bitfield,
            int capabilities,
            long uploadBudget,
            Double uploadLoad
    ) {
        static Candidate from(PeerRecord peer) {
            return new Candidate(
                    peer.peerId().toHex(),
                    peer.ip(),
                    peer.port(),
                    peer.siteId(),
                    peer.networkGroupId(),
                    new AnnounceRequest.Bitfield(peer.bitCount(), Hex.toLowerHex(peer.bits())),
                    peer.capabilities(),
                    peer.uploadBudgetBytesPerSecond(),
                    peer.uploadLoad().isPresent() ? peer.uploadLoad().getAsDouble() : null);
        }
    }
}
