package com.prabin.swarmedge.origin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OriginHttpServerTest {

    @TempDir
    Path tempDir;

    @Test
    void servesWholeFile() throws Exception {
        Path root = tempDir.resolve("origin");
        Files.createDirectories(root);
        Files.write(root.resolve("ten.bin"), new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

        try (OriginHttpServer server = new OriginHttpServer(root)) {
            HttpURLConnection conn = get(server, "/files/ten.bin");
            assertThat(conn.getResponseCode()).isEqualTo(200);
            assertThat(conn.getInputStream().readAllBytes()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
            conn.disconnect();
        }
    }

    @Test
    void missingFileIs404() throws Exception {
        Path root = tempDir.resolve("origin");
        Files.createDirectories(root);

        try (OriginHttpServer server = new OriginHttpServer(root)) {
            HttpURLConnection conn = get(server, "/files/missing.bin");
            assertThat(conn.getResponseCode()).isEqualTo(404);
            conn.disconnect();
        }
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        Path root = tempDir.resolve("origin");
        Files.createDirectories(root);
        Files.write(tempDir.resolve("secret.bin"), new byte[] {9});

        try (OriginHttpServer server = new OriginHttpServer(root)) {
            HttpURLConnection conn = get(server, "/files/../secret.bin", null);
            assertThat(conn.getResponseCode()).isEqualTo(404);
            conn.disconnect();
        }
    }

    @Test
    void rangedGetReturnsPartialContent() throws Exception {
        Path root = tempDir.resolve("origin");
        Files.createDirectories(root);
        Files.write(root.resolve("ten.bin"), new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

        try (OriginHttpServer server = new OriginHttpServer(root)) {
            HttpURLConnection conn = get(server, "/files/ten.bin", "bytes=0-3");
            assertThat(conn.getResponseCode()).isEqualTo(206);
            assertThat(conn.getHeaderField("Content-Range")).isEqualTo("bytes 0-3/10");
            assertThat(conn.getInputStream().readAllBytes()).containsExactly(0, 1, 2, 3);
            conn.disconnect();
        }
    }

    @Test
    void openEndedRangeGoesToEndOfFile() throws Exception {
        Path root = tempDir.resolve("origin");
        Files.createDirectories(root);
        Files.write(root.resolve("ten.bin"), new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

        try (OriginHttpServer server = new OriginHttpServer(root)) {
            HttpURLConnection conn = get(server, "/files/ten.bin", "bytes=8-");
            assertThat(conn.getResponseCode()).isEqualTo(206);
            assertThat(conn.getHeaderField("Content-Range")).isEqualTo("bytes 8-9/10");
            assertThat(conn.getInputStream().readAllBytes()).containsExactly(8, 9);
            conn.disconnect();
        }
    }

    @Test
    void unsatisfiableRangeIs416() throws Exception {
        Path root = tempDir.resolve("origin");
        Files.createDirectories(root);
        Files.write(root.resolve("ten.bin"), new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

        try (OriginHttpServer server = new OriginHttpServer(root)) {
            HttpURLConnection conn = get(server, "/files/ten.bin", "bytes=99-100");
            assertThat(conn.getResponseCode()).isEqualTo(416);
            assertThat(conn.getHeaderField("Content-Range")).isEqualTo("bytes */10");
            conn.disconnect();
        }
    }

    private static HttpURLConnection get(OriginHttpServer server, String path) throws Exception {
        return get(server, path, null);
    }

    private static HttpURLConnection get(OriginHttpServer server, String path, String range) throws Exception {
        URI uri = URI.create(server.baseUri() + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        if (range != null) {
            conn.setRequestProperty("Range", range);
        }
        conn.connect();
        return conn;
    }
}
