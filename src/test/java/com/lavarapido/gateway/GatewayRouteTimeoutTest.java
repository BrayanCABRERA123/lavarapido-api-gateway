package com.lavarapido.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-16: el gateway no espera para siempre a un servicio colgado. booking falso tarda 5 s en
 * /api/v1/bookings/slow y el gateway solo espera 1 s en esta prueba.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.http.clients.read-timeout=1s"
})
class GatewayRouteTimeoutTest {

    private static HttpServer booking;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) throws IOException {
        booking = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        booking.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        booking.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/slow")) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "booking".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (IOException gatewayAlreadyLeft) {
                // el gateway ya cortó la conexión: es justo lo que se prueba
            }
        });
        booking.start();
        registry.add("ROUTE_BOOKING_URL", () -> "http://localhost:" + booking.getAddress().getPort());
    }

    @AfterAll
    static void stopBooking() {
        booking.stop(0);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aServiceThatDoesNotAnswerIsCutAtTheReadTimeout() throws Exception {
        Instant start = Instant.now();
        HttpResponse<String> response = get("/api/v1/bookings/slow");
        Duration waited = Duration.between(start, Instant.now());

        assertTrue(response.statusCode() >= 500, "el gateway debe responder un error, respondió " + response.statusCode());
        assertTrue(waited.compareTo(Duration.ofSeconds(4)) < 0, "esperó " + waited);
    }

    @Test
    void aServiceThatAnswersInTimeGoesThrough() throws Exception {
        HttpResponse<String> response = get("/api/v1/bookings/me");

        assertEquals(200, response.statusCode());
        assertEquals("booking", response.body());
    }
}
