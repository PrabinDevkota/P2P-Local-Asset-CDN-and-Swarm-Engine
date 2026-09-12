package com.prabin.swarmedge.origin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only origin: stream files and signed-manifest JSON from a local directory.
 * Trust is still the signed manifest; this server is an untrusted byte source.
 */
public final class OriginHttpServer implements AutoCloseable {

    private final Path root;
    private final HttpServer server;
    private final ExecutorService executor;
    private final OriginByteLedger ledger = new OriginByteLedger();

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
        this.server.createContext("/files",
                exchange -> handleGet(exchange, "/files/", "application/octet-stream", false));
        this.server.createContext("/manifests",
                exchange -> handleGet(exchange, "/manifests/", "application/json", true));
        this.server.setExecutor(executor);
        this.server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + port());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * Bytes this origin actually put on the wire, per {@code ?runId=} and asset name.
     * Baseline B0 reads this; nothing in the serving path branches on it.
     */
    public OriginByteLedger ledger() {
        return ledger;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /**
     * Stream a file under {@code root}. Manifest URLs copy JSON bytes only —
     * this server never parses or verifies a signature.
     */
    private void handleGet(HttpExchange exchange, String prefix, String contentType, boolean jsonName)
            throws IOException {
        boolean headersSent = false;
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange, 405);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path == null || !path.startsWith(prefix) || path.length() == prefix.length()) {
                sendEmpty(exchange, 404);
                return;
            }
            String name = path.substring(prefix.length());
            if (jsonName && !name.toLowerCase(Locale.ROOT).endsWith(".json")) {
                sendEmpty(exchange, 404);
                return;
            }
            Path file = resolveSafe(name);
            if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                sendEmpty(exchange, 404);
                return;
            }
            long size = Files.size(file);
            String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
            ByteSpan span = rangeHeader == null || rangeHeader.isBlank()
                    ? ByteSpan.full(size)
                    : ByteSpan.parse(rangeHeader, size);
            if (span == null) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                sendEmpty(exchange, 416);
                return;
            }
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("Content-Type", contentType);
            int status = span.partial() ? 206 : 200;
            if (span.partial()) {
                exchange.getResponseHeaders().set(
                        "Content-Range", "bytes " + span.start() + "-" + span.end() + "/" + size);
            }
            exchange.sendResponseHeaders(status, span.length());
            headersSent = true;
            AtomicLong served = new AtomicLong();
            try {
                copy(file, span, exchange.getResponseBody(), served);
            } finally {
                // Count what reached the socket, so an aborted transfer is not billed as delivered.
                ledger.recordServed(runIdOf(exchange), name, served.get());
            }
        } catch (IOException e) {
            if (!headersSent) {
                sendEmpty(exchange, 500);
            }
        }
    }

    /** {@code ?runId=…} attributes bytes to a benchmark run. Absent means unattributed. */
    private static String runIdOf(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isBlank()) {
            return OriginByteLedger.UNATTRIBUTED_RUN;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "runId".equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return OriginByteLedger.UNATTRIBUTED_RUN;
    }

    private Path resolveSafe(String name) {
        if (name.isBlank() || name.contains("\\") || name.indexOf('\0') >= 0) {
            return null;
        }
        Path relative = Path.of(name);
        if (relative.isAbsolute()) {
            return null;
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            return null;
        }
        return resolved;
    }

    /** Returns through {@code served} the bytes written, including a partial write that then failed. */
    private static void copy(Path file, ByteSpan span, OutputStream out, AtomicLong served) throws IOException {
        try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ);
             OutputStream body = out) {
            if (span.length() == 0) {
                return;
            }
            WritableByteChannel dest = Channels.newChannel(body);
            long remaining = span.length();
            long pos = span.start();
            ByteBuffer fallback = null;
            boolean transferToWorks = true;
            while (remaining > 0) {
                if (transferToWorks) {
                    long n = in.transferTo(pos, remaining, dest);
                    if (n > 0) {
                        pos += n;
                        remaining -= n;
                        served.addAndGet(n);
                        continue;
                    }
                    transferToWorks = false;
                }
                if (fallback == null) {
                    fallback = ByteBuffer.allocate((int) Math.min(remaining, 64 * 1024));
                }
                fallback.clear();
                if (fallback.capacity() > remaining) {
                    fallback.limit((int) remaining);
                }
                int read = in.read(fallback, pos);
                if (read <= 0) {
                    throw new IOException("short transfer at offset " + pos);
                }
                fallback.flip();
                body.write(fallback.array(), fallback.position(), fallback.remaining());
                pos += read;
                remaining -= read;
                served.addAndGet(read);
            }
        }
    }

    private static void sendEmpty(HttpExchange exchange, int code) throws IOException {
        exchange.sendResponseHeaders(code, -1);
        exchange.close();
    }

    /** Inclusive start/end; {@code parse} returns null when Range is malformed or unsatisfiable. */
    record ByteSpan(long start, long end, long length, boolean partial) {
        static ByteSpan full(long size) {
            if (size == 0) {
                return new ByteSpan(0, -1, 0, false);
            }
            return new ByteSpan(0, size - 1, size, false);
        }

        static ByteSpan parse(String header, long size) {
            if (size <= 0 || !header.regionMatches(true, 0, "bytes=", 0, 6)) {
                return null;
            }
            String spec = header.substring(6).trim();
            if (spec.isEmpty() || spec.contains(",")) {
                return null;
            }
            try {
                if (spec.charAt(0) == '-') {
                    long suffix = Long.parseLong(spec.substring(1));
                    if (suffix <= 0) {
                        return null;
                    }
                    long start = Math.max(0, size - suffix);
                    return new ByteSpan(start, size - 1, size - start, true);
                }
                int dash = spec.indexOf('-');
                if (dash <= 0) {
                    return null;
                }
                long start = Long.parseLong(spec.substring(0, dash));
                if (start < 0 || start >= size) {
                    return null;
                }
                String endPart = spec.substring(dash + 1);
                long end = endPart.isEmpty() ? size - 1 : Long.parseLong(endPart);
                if (end < start) {
                    return null;
                }
                end = Math.min(end, size - 1);
                return new ByteSpan(start, end, end - start + 1, true);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
