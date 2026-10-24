package com.lavarapido.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SEC-04: tope de 3 peticiones por minuto por IP en estas pruebas. */
class AuthRateLimitFilterTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-23T12:00:00Z"));
    private final AuthRateLimitFilter filter = new AuthRateLimitFilter(
            new ClientIpResolver(ClientIpResolver.DEFAULT_TRUSTED_PROXIES), 3, Duration.ofMinutes(1), clock);

    private record Result(MockFilterChain chain, MockHttpServletResponse response) {
        boolean wentThrough() {
            return chain.getRequest() != null;
        }
    }

    private Result call(String method, String path, String clientIp) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        // como en producción: llega desde Caddy con la IP del cliente
        request.setRemoteAddr("172.18.0.5");
        request.addHeader("X-Forwarded-For", clientIp);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(chain, response);
    }

    private Result login(String clientIp) throws Exception {
        return call("POST", "/api/v1/auth/login", clientIp);
    }

    @Test
    void afterTheLimitTheAddressGets429RateLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertTrue(login("203.0.113.7").wentThrough());
        }

        Result blocked = login("203.0.113.7");

        assertNull(blocked.chain().getRequest(), "no debe llegar a security-service");
        assertEquals(429, blocked.response().getStatus());
        assertEquals("application/problem+json", blocked.response().getContentType());
        assertTrue(blocked.response().getContentAsString().contains("\"code\":\"RATE_LIMITED\""));
        assertEquals("20", blocked.response().getHeader("Retry-After"));
    }

    @Test
    void eachAddressHasItsOwnLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("203.0.113.7");
        }

        assertTrue(login("198.51.100.20").wentThrough());
    }

    @Test
    void theLimitRefillsWithTime() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("203.0.113.7");
        }

        clock.advance(Duration.ofSeconds(20));
        assertTrue(login("203.0.113.7").wentThrough());
        assertEquals(429, login("203.0.113.7").response().getStatus());
    }

    @Test
    void everyAuthEndpointCountsTogether() throws Exception {
        call("POST", "/api/v1/auth/password/forgot", "203.0.113.7");
        call("POST", "/api/v1/auth/register", "203.0.113.7");
        call("POST", "/api/v1/auth/password/verify", "203.0.113.7");

        assertEquals(429, login("203.0.113.7").response().getStatus());
    }

    @Test
    void anEncodedOrUnnormalizedPathIsStillTheLogin() throws Exception {
        // el gateway y security decodifican la ruta: "%61uth" llega al login igual
        for (int i = 0; i < 3; i++) {
            login("203.0.113.7");
        }

        assertEquals(429, call("POST", "/api/v1/%61uth/login", "203.0.113.7").response().getStatus());
        assertEquals(429, call("POST", "//api/v1/auth/login", "203.0.113.7").response().getStatus());
        assertEquals(429, call("POST", "/api/v1/x/../auth/login", "203.0.113.7").response().getStatus());
    }

    @Test
    void otherRoutesAndPreflightsAreNotLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertNotNull(call("GET", "/api/v1/catalog/services", "203.0.113.7").chain().getRequest());
            assertNotNull(call("OPTIONS", "/api/v1/auth/login", "203.0.113.7").chain().getRequest());
        }
        assertTrue(login("203.0.113.7").wentThrough());
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
