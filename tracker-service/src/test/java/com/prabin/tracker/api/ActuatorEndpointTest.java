package com.prabin.tracker.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorEndpointTest {

    @LocalServerPort
    int port;

    @Test
    void healthAndPrometheusAreExposed() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> health = client.send(request("/actuator/health"), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isIn(200, 503);
        assertThat(health.body()).contains("status");

        HttpResponse<String> metrics = client.send(request("/actuator/prometheus"), HttpResponse.BodyHandlers.ofString());
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("jvm_");
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
    }
}
