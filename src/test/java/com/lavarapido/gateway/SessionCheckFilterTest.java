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
 * falso: "abierta" responde 204, "cerrada" 401 y "caido" 500. Como el real, acepta el esquema
 * Bearer sin distinguir mayúsculas y a cualquier otro esquema le responde 401.
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
            String token = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7) : null;
            if ("abierta".equals(token)) {
                exchange.sendResponseHeaders(204, -1);
            } else if (token == null || "cerrada".equals(token)) {
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
        return callWithAuthorization(path, token == null ? null : "Bearer " + token);
    }

    private MockFilterChain callWithAuthorization(String path, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
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
    void aClosedSessionIsCutWhateverTheCaseOfTheScheme() throws Exception {
        // SEC-01: los servicios aceptan "bearer" y "BEARER"; antes esos pasaban sin revisar
        assertNull(callWithAuthorization("/api/v1/payments", "bearer cerrada").getRequest());
        assertNull(callWithAuthorization("/api/v1/bookings", "BEARER cerrada").getRequest());
        assertNotNull(callWithAuthorization("/api/v1/bookings", "bEaReR abierta").getRequest());
        // "BEARER cerrada" usó la respuesta guardada de "bearer cerrada": es el mismo token
        assertEquals(2, calls.get());
    }

    @Test
    void anyOtherAuthorizationIsCheckedToo() throws Exception {
        // security responde 401 a lo que no es un token: no se deja pasar algo que no se pudo revisar
        assertNull(callWithAuthorization("/api/v1/bookings", "Basic YWRtaW46YWRtaW4=").getRequest());
        assertNull(callWithAuthorization("/api/v1/bookings", "Token cerrada").getRequest());
        assertEquals(2, calls.get());
    }

    @Test
    void pathsOutsideApiAreCheckedToo() throws Exception {
        // la ruta no decide si se revisa: "//api/..." o cualquier otra con token también pasa por security
        assertNull(call("//api/v1/bookings", "cerrada").getRequest());
        assertNull(call("/otra/ruta", "cerrada").getRequest());
    }

    @Test
    void anEmptyAuthorizationIsTreatedAsNoToken() throws Exception {
        assertNotNull(callWithAuthorization("/api/v1/catalog/services", "   ").getRequest());
        assertEquals(0, calls.get());
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
    void theCachedAnswerIsPerTokenWhateverTheScheme() throws Exception {
        callWithAuthorization("/api/v1/bookings", "Bearer abierta");
        callWithAuthorization("/api/v1/bookings", "bearer abierta");
        callWithAuthorization("/api/v1/bookings", "BEARER abierta");

        assertEquals(1, calls.get());
    }

    @Test
    void aLogoutWithAnySchemeForgetsEveryVariantOfTheToken() throws Exception {
        // SEC-01: si la llave fuera el encabezado completo, "bearer" seguiría guardado hasta 30 s
        for (String logoutScheme : new String[]{"bearer", "Bearer", "BEARER"}) {
            callWithAuthorization("/api/v1/bookings", "Bearer abierta");
            callWithAuthorization("/api/v1/bookings", "bearer abierta");
            callWithAuthorization("/api/v1/bookings", "BEARER abierta");
            int beforeLogout = calls.get();
            assertNotNull(callWithAuthorization("/api/v1/auth/logout", logoutScheme + " abierta").getRequest());

            callWithAuthorization("/api/v1/bookings", "bearer abierta");
            assertEquals(beforeLogout + 1, calls.get(), "después del logout con " + logoutScheme + " se vuelve a preguntar");
        }
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
