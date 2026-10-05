package com.lavarapido.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cada ruta llega al servicio correcto. Los 4 servicios se reemplazan por servidores HTTP falsos
 * que responden "<servicio> <ruta>" y guardan los encabezados que les llegaron.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutesTest {

    private static final Map<String, HttpServer> SERVERS = new ConcurrentHashMap<>();
    private static final Map<String, String> LAST_AUTHORIZATION = new ConcurrentHashMap<>();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) throws IOException {
        for (String service : List.of("security", "customer", "booking", "notification")) {
            HttpServer server = fakeService(service);
            SERVERS.put(service, server);
            registry.add("ROUTE_" + service.toUpperCase() + "_URL",
                    () -> "http://localhost:" + server.getAddress().getPort());
        }
    }

    private static HttpServer fakeService(String name) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                LAST_AUTHORIZATION.put(name, auth);
            }
            // como los servicios reales: devuelven su propio CORS (el gateway debe quitarlo)
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "http://localhost:4200");
            exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
            byte[] body = (name + " " + exchange.getRequestURI()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    @AfterAll
    static void stopServers() {
        SERVERS.values().forEach(server -> server.stop(0));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer token-de-prueba").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/v1/auth/login, security",
            "/api/v1/users/me, security",
            "/api/v1/admin/users, security",
            "/api/v1/vehicles, customer",
            "/api/v1/vehicles/2, customer",
            "/api/v1/vehicle-types, customer",
            "/api/v1/admin/vehicles, customer",
            "/api/v1/catalog/services, booking",
            "/api/v1/schedule/business-hours, booking",
            "/api/v1/establishment, booking",
            "/api/v1/bookings/me, booking",
            "/api/v1/admin/bookings, booking",
            "/api/v1/admin/bays/1, booking",
            "/api/v1/operator/bookings, booking",
            "/api/v1/notifications/unread-count, notification",
            "/api/v1/admin/notifications, notification"
    })
    void routesEachPathToItsService(String path, String service) throws Exception {
        HttpResponse<String> response = get(path);

        assertEquals(200, response.statusCode());
        assertEquals(service + " " + path, response.body());
    }

    @Test
    void forwardsTheQueryStringAndTheToken() throws Exception {
        HttpResponse<String> response = get("/api/v1/bookings/availability?date=2026-10-05&vehicleTypeId=1&serviceIds=1");

        assertEquals("booking /api/v1/bookings/availability?date=2026-10-05&vehicleTypeId=1&serviceIds=1",
                response.body());
        assertEquals("Bearer token-de-prueba", LAST_AUTHORIZATION.get("booking"));
    }

    @Test
    void internalRoutesAreNotExposed() throws Exception {
        assertEquals(404, get("/internal/v1/users/1/contact").statusCode());
    }

    @Test
    void unknownRoutesAreNotFound() throws Exception {
        assertEquals(404, get("/api/v1/does-not-exist").statusCode());
    }

    @Test
    void preflightFromTheWebIsAnsweredWithCors() throws Exception {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bookings/me"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization").build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(List.of("http://localhost:4200"), response.headers().allValues("Access-Control-Allow-Origin"));
    }

    @Test
    void corsHeaderIsNotDuplicated() throws Exception {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bookings/me"))
                        .header("Origin", "http://localhost:4200").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(List.of("http://localhost:4200"), response.headers().allValues("Access-Control-Allow-Origin"));
        assertEquals(1, response.headers().allValues("Access-Control-Allow-Credentials").size());
    }

    @Test
    void unknownOriginsAreRejected() throws Exception {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bookings/me"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET").build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
    }

    @Test
    void healthIsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertEquals(200, response.statusCode());
    }
}
