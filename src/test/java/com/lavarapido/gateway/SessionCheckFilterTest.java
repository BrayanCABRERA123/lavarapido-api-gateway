package com.lavarapido.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * El gateway corta los tokens de sesiones cerradas. security-service se reemplaza por un servidor
 * falso: "abierta" responde 204, "cerrada" 401 y "caido" 500.
 */
class SessionCheckFilterTest {

    private static final String PROBLEM = "{\"status\":401,\"code\":\"UNAUTHORIZED\"}";

    private HttpServer security;
    private final AtomicInteger calls = new AtomicInteger();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-21T12:00:00Z"));
    private SessionCheckFilter filter;

    @BeforeEach
    void startSecurity() throws IOException {
        security = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        security.createContext("/api/v1/auth/session", exchange -> {
            calls.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if ("Bearer abierta".equals(auth)) {
                exchange.sendResponseHeaders(204, -1);
            } else if ("Bearer cerrada".equals(auth)) {
                byte[] body = PROBLEM.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                exchange.sendResponseHeaders(401, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } else {
                exchange.sendResponseHeaders(500, -1);
            }
            exchange.close();
        });
        security.start();
        filter = new SessionCheckFilter(
                URI.create("http://localhost:" + security.getAddress().getPort() + "/api/v1/auth/session"),
                Duration.ofSeconds(30), Duration.ofSeconds(2), clock);
    }

    @AfterEach
    void stopSecurity() {
        security.stop(0);
    }

    private MockFilterChain call(String path, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @Test
    void anOpenSessionGoesThroughToTheService() throws Exception {
        assertNotNull(call("/api/v1/bookings", "abierta").getRequest());
    }

    @Test
    void aClosedSessionIsCutWithSecurityServicesOwn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments");
        request.addHeader("Authorization", "Bearer cerrada");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "la petición no debe llegar al servicio");
        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertEquals("application/problem+json", response.getContentType());
        assertEquals(PROBLEM, response.getContentAsString());
    }

    @Test
    void theAnswerIsRememberedForAWhilePerToken() throws Exception {
        call("/api/v1/bookings", "abierta");
        call("/api/v1/vehicles", "abierta");
        assertEquals(1, calls.get());

        clock.advance(Duration.ofSeconds(31));
        call("/api/v1/bookings", "abierta");
        assertEquals(2, calls.get());
    }

    @Test
    void afterLogoutThroughTheGatewayTheTokenIsAskedAgainRightAway() throws Exception {
        call("/api/v1/bookings", "abierta");
        assertNotNull(call("/api/v1/auth/logout", "abierta").getRequest());
        call("/api/v1/bookings", "abierta");

        assertEquals(2, calls.get());
    }

    @Test
    void ifSecurityServiceFailsTheRequestGoesOn() throws Exception {
        assertNotNull(call("/api/v1/bookings", "caido").getRequest());
        security.stop(0);
        assertNotNull(call("/api/v1/bookings", "abierta").getRequest());
    }

    @Test
    void publicCallsLoginAndPreflightAreNotChecked() throws Exception {
        assertNotNull(call("/api/v1/catalog/services", null).getRequest());
        assertNotNull(call("/api/v1/auth/logout", "cerrada").getRequest());

        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/v1/bookings");
        preflight.addHeader("Authorization", "Bearer cerrada");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(preflight, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        assertEquals(0, calls.get());
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
