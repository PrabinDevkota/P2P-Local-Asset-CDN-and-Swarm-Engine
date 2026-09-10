package com.prabin.swarmedge.origin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;

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

    @Test
    void servesManifestJsonBytesWithoutParsing() throws Exception {
        Path root = tempDir.resolve("origin");
        Files.createDirectories(root);
        byte[] json = "{\"schemaVersion\":1,\"signature\":\"dGVzdHNpZw==\"}".getBytes(UTF_8);
        Files.write(root.resolve("game-x.json"), json);

        try (OriginHttpServer server = new OriginHttpServer(root)) {
            HttpURLConnection conn = get(server, "/manifests/game-x.json");
            assertThat(conn.getResponseCode()).isEqualTo(200);
            assertThat(conn.getHeaderField("Content-Type")).startsWith("application/json");
            assertThat(conn.getInputStream().readAllBytes()).isEqualTo(json);
            conn.disconnect();
        }
    }

    @Test
    void servesGarbageManifestJsonAsOpaqueBytes() throws Exception {
        Path root = tempDir.resolve("origin");
        Files.createDirectories(root);
        byte[] garbage = "{not-valid".getBytes(UTF_8);
        Files.write(root.resolve("broken.json"), garbage);

        try (OriginHttpServer server = new OriginHttpServer(root)) {
            HttpURLConnection conn = get(server, "/manifests/broken.json");
            assertThat(conn.getResponseCode()).isEqualTo(200);
            assertThat(conn.getInputStream().readAllBytes()).isEqualTo(garbage);
            conn.disconnect();
        }
    }

    @Test
    void missingManifestIs404() throws Exception {
        Path root = tempDir.resolve("origin");
        Files.createDirectories(root);

        try (OriginHttpServer server = new OriginHttpServer(root)) {
            HttpURLConnection conn = get(server, "/manifests/missing.json");
            assertThat(conn.getResponseCode()).isEqualTo(404);
            conn.disconnect();
        }
    }

    @Test
    void rejectsManifestPathTraversal() throws Exception {
        Path root = tempDir.resolve("origin");
        Files.createDirectories(root);
        Files.write(tempDir.resolve("secret.json"), "{\"secret\":true}".getBytes(UTF_8));

        try (OriginHttpServer server = new OriginHttpServer(root)) {
            assertThat(rawGetStatus(server, "/manifests/../secret.json")).isEqualTo(404);
            assertThat(rawGetStatus(server, "/manifests/%2e%2e/secret.json")).isEqualTo(404);
        }
    }

    @Test
    void rejectsNonJsonManifestName() throws Exception {
        Path root = tempDir.resolve("origin");
        Files.createDirectories(root);
        Files.write(root.resolve("ten.bin"), new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

        try (OriginHttpServer server = new OriginHttpServer(root)) {
            HttpURLConnection conn = get(server, "/manifests/ten.bin");
            assertThat(conn.getResponseCode()).isEqualTo(404);
            conn.disconnect();
        }
    }

    @Test
    void rangedManifestGetReturnsPartialJson() throws Exception {
        Path root = tempDir.resolve("origin");
        Files.createDirectories(root);
        byte[] json = "{\"schemaVersion\":1}".getBytes(UTF_8);
        Files.write(root.resolve("game-x.json"), json);

        try (OriginHttpServer server = new OriginHttpServer(root)) {
            HttpURLConnection conn = get(server, "/manifests/game-x.json", "bytes=0-3");
            assertThat(conn.getResponseCode()).isEqualTo(206);
            assertThat(conn.getHeaderField("Content-Range")).isEqualTo("bytes 0-3/" + json.length);
            assertThat(conn.getInputStream().readAllBytes()).isEqualTo(new byte[] {json[0], json[1], json[2], json[3]});
            conn.disconnect();
        }
    }

    private static int rawGetStatus(OriginHttpServer server, String path) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write((
                    "GET " + path + " HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Connection: close\r\n\r\n").getBytes(UTF_8));
            String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8)).readLine();
            return Integer.parseInt(statusLine.split(" ")[1]);
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
