package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.Ed25519Keys;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ManifestSigner;
import com.prabin.swarmedge.manifest.ManifestVerifier;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.origin.OriginHttpServer;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Random;

/**
 * P12-01: one origin, one EDGE, eight seeders, then the same leecher again with a warm cache.
 *
 * <p>The Prometheus text is the bytes, dials, and cache this run actually recorded.
 * It does not compute an offload ratio.
 */
public final class DemoRun {

    private DemoRun() {
    }

    public static void main(String[] args) throws Exception {
        Path root = locateRepo();
        Path work = Files.createTempDirectory("swarmedge-demo");
        try {
            Report report = run(work, root.resolve("research/configs/demo-cold-warm.yaml"));
            Path out = root.resolve("research/processed/demo-summary.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, table(report));
            System.out.println(table(report));
            System.out.println(out.toAbsolutePath());
            boolean serve = Arrays.asList(args).contains("--serve");
            if (!serve) {
                return;
            }
            int port = Integer.parseInt(System.getenv().getOrDefault("DEMO_METRICS_PORT", "9109"));
            HttpServer server = serve(report.prometheus(), port);
            System.out.println("metrics http://127.0.0.1:" + server.getAddress().getPort() + "/metrics");
            Thread.currentThread().join();
        } finally {
            if (!Arrays.asList(args).contains("--serve")) {
                deleteTree(work);
            }
        }
    }

    private static Path locateRepo() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("mvnw.cmd")) && !Files.isRegularFile(dir.resolve("mvnw"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("run the demo from the repository");
        }
        return dir;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort; the demo directory is a temp tree
                }
            });
        }
    }

    public record Report(SwarmRunner.RunResult cold, SwarmRunner.RunResult warm, String prometheus) {
    }

    public static Report run(Path work, Path configFile) throws Exception {
        SwarmScenarioConfig config = SwarmScenarioConfig.load(configFile);
        if (config.seederCount() != 8 || config.edgeCount() != 1 || config.repetitions() != 2) {
            throw new IllegalArgumentException("demo config must name 8 seeders, 1 edge, and 2 repetitions");
        }
        int chunkSize = (int) config.chunkSizeBytes();
        int bytes = (int) config.sizeBytes();
        byte[] original = new byte[bytes];
        new Random(config.seed()).nextBytes(original);
        Path originRoot = Files.createDirectories(work.resolve("origin"));
        Path published = Files.write(originRoot.resolve(config.fileName()), original);
        KeyPair keys = Ed25519Keys.generate();
        var chunks = new FileChunker(chunkSize).chunk(published);
        ReleaseManifest manifest = ManifestSigner.sign(
                ReleaseManifestFactory.unsigned("game-x", "1.4.0", published, chunkSize, chunks,
                        "2026-09-26T00:00:00Z", "2027-12-31T00:00:00Z", 1, "release-key-2026-01"),
                keys.getPrivate());
        ManifestVerifier.verify(manifest, keys.getPublic());
        AssetId assetId = AssetId.of(filled((byte) 0x11, 32));

        try (OriginHttpServer origin = new OriginHttpServer(originRoot)) {
            SwarmRunner runner = new SwarmRunner(config, work.resolve("swarm"), published, assetId,
                    origin.baseUri(), origin.ledger());
            SwarmRunner.Summary summary = runner.run(manifest);
            if (summary.runs().size() != 2) {
                throw new IllegalStateException("demo expected a cold pass and a warm pass");
            }
            SwarmRunner.RunResult cold = summary.runs().get(0);
            SwarmRunner.RunResult warm = summary.runs().get(1);
            return new Report(cold, warm, prometheus(cold, warm, chunks));
        }
    }

    public static String table(Report report) {
        return """
                # Demo

                Eight seeders and one EDGE on loopback. The second pass keeps the leecher cache.
                These are measured bytes and times. This table does not rank the passes.

                | pass | peer bytes | edge bytes | origin bytes | cache bytes | peers dialled | elapsed ms |
                | --- | --- | --- | --- | --- | --- | --- |
                | cold | %d | %d | %d | %d | %d | %d |
                | warm | %d | %d | %d | %d | %d | %d |
                """.formatted(
                report.cold().peerBytes(), report.cold().edgeBytes(), report.cold().originBytes(),
                report.cold().cacheBytes(), report.cold().peersDialled(), report.cold().elapsedMillis(),
                report.warm().peerBytes(), report.warm().edgeBytes(), report.warm().originBytes(),
                report.warm().cacheBytes(), report.warm().peersDialled(), report.warm().elapsedMillis());
    }

    public static HttpServer serve(String prometheus, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        byte[] body = prometheus.getBytes(StandardCharsets.UTF_8);
        server.createContext("/metrics", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    static String prometheus(SwarmRunner.RunResult cold, SwarmRunner.RunResult warm, java.util.List<ChunkEntry> chunks) {
        long chunk = chunks.getFirst().length();
        StringBuilder out = new StringBuilder();
        gauge(out, "swarmedge_demo_origin_bytes", "Origin bytes billed to this pass");
        sample(out, "swarmedge_demo_origin_bytes", "cold", cold.originBytes());
        sample(out, "swarmedge_demo_origin_bytes", "warm", warm.originBytes());
        gauge(out, "swarmedge_demo_peer_bytes", "Desktop seeder bytes sent on this pass");
        sample(out, "swarmedge_demo_peer_bytes", "cold", cold.peerBytes());
        sample(out, "swarmedge_demo_peer_bytes", "warm", warm.peerBytes());
        gauge(out, "swarmedge_demo_edge_bytes", "EDGE bytes sent on this pass");
        sample(out, "swarmedge_demo_edge_bytes", "cold", cold.edgeBytes());
        sample(out, "swarmedge_demo_edge_bytes", "warm", warm.edgeBytes());
        gauge(out, "swarmedge_demo_completion_millis", "Elapsed milliseconds for this pass");
        sample(out, "swarmedge_demo_completion_millis", "cold", cold.elapsedMillis());
        sample(out, "swarmedge_demo_completion_millis", "warm", warm.elapsedMillis());
        gauge(out, "swarmedge_demo_active_peers", "Peers dialled on this pass");
        sample(out, "swarmedge_demo_active_peers", "cold", cold.peersDialled());
        sample(out, "swarmedge_demo_active_peers", "warm", warm.peersDialled());
        gauge(out, "swarmedge_demo_cache_hits", "Chunks already verified in the leecher cache before this pass");
        sample(out, "swarmedge_demo_cache_hits", "cold", cold.cacheBytes() / chunk);
        sample(out, "swarmedge_demo_cache_hits", "warm", warm.cacheBytes() / chunk);
        return out.toString();
    }

    private static void gauge(StringBuilder out, String name, String help) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(" gauge\n");
    }

    private static void sample(StringBuilder out, String name, String pass, long value) {
        out.append(name).append("{pass=\"").append(pass).append("\"} ").append(value).append('\n');
    }

    private static byte[] filled(byte value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, value);
        return out;
    }
}
