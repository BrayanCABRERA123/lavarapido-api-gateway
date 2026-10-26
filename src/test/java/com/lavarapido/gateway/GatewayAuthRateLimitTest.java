package com.lavarapido.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-04 con el gateway completo: el tope se aplica antes de llegar a security-service y el 429 sale
 * con su CORS, así la web puede leer el código. Tope de 2 peticiones por minuto en esta prueba.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.auth.requests=2",
        "app.rate-limit.auth.window=1m"
})
class GatewayAuthRateLimitTest {

    private static final AtomicInteger LOGINS = new AtomicInteger();
    private static HttpServer security;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) throws IOException {
        security = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        security.createContext("/", exchange -> {
            LOGINS.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        security.start();
        registry.add("ROUTE_SECURITY_URL", () -> "http://localhost:" + security.getAddress().getPort());
    }

    @AfterAll
    static void stopSecurity() {
        security.stop(0);
    }

    private HttpResponse<String> login() throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                        .header("Origin", "http://localhost:4200")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theThirdLoginInAMinuteIsAnswered429WithCors() throws Exception {
        assertEquals(204, login().statusCode());
        assertEquals(204, login().statusCode());

        HttpResponse<String> blocked = login();

        assertEquals(429, blocked.statusCode());
        assertEquals(2, LOGINS.get(), "la tercera no debe llegar a security-service");
        assertTrue(blocked.body().contains("\"code\":\"RATE_LIMITED\""));
        assertTrue(blocked.headers().firstValue("Retry-After").isPresent());
        assertEquals("http://localhost:4200", blocked.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }
}
