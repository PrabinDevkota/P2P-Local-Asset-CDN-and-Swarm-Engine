package com.prabin.swarmedge.origin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test-only origin: serve files from a local directory over HTTP.
 * Trust is still the signed manifest; this server is an untrusted byte source.
 */
public final class OriginHttpServer implements AutoCloseable {

    private final Path root;
    private final HttpServer server;
    private final ExecutorService executor;

    public OriginHttpServer(Path root) throws IOException {
        this(root, 0);
    }

    public OriginHttpServer(Path root, int port) throws IOException {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "origin-http");
            t.setDaemon(true);
            return t;
        });
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/files", this::handleFiles);
        this.server.setExecutor(executor);
        this.server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + port());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handleFiles(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, new byte[0]);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String prefix = "/files/";
            if (path == null || !path.startsWith(prefix) || path.length() == prefix.length()) {
                send(exchange, 404, new byte[0]);
                return;
            }
            Path file = resolveSafe(path.substring(prefix.length()));
            if (file == null || !Files.isRegularFile(file)) {
                send(exchange, 404, new byte[0]);
                return;
            }
            byte[] body = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            send(exchange, 200, body);
        } catch (IOException e) {
            send(exchange, 500, new byte[0]);
        }
    }

    private Path resolveSafe(String name) {
        if (name.isBlank() || name.contains("\\") || name.indexOf('\0') >= 0) {
            return null;
        }
        Path resolved = root.resolve(name).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            return null;
        }
        return resolved;
    }

    private static void send(HttpExchange exchange, int code, byte[] body) throws IOException {
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
