package com.lavarapido.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** La IP del cliente solo sale de X-Forwarded-For si la conexión viene de un proxy de confianza. */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(ClientIpResolver.DEFAULT_TRUSTED_PROXIES);

    private static MockHttpServletRequest from(String remoteAddr, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(remoteAddr);
        for (String header : forwardedFor) {
            request.addHeader("X-Forwarded-For", header);
        }
        return request;
    }

    @Test
    void behindCaddyTheClientComesFromXForwardedFor() {
        // Caddy está en la red de Docker (172.16.0.0/12) y pone la IP de quien se conectó
        assertEquals("203.0.113.7", resolver.resolve(from("172.18.0.5", "203.0.113.7")));
    }

    @Test
    void aClientConnectingDirectlyCannotChooseItsIp() {
        assertEquals("198.51.100.20", resolver.resolve(from("198.51.100.20", "1.2.3.4")));
    }

    @Test
    void whatTheClientWroteToTheLeftOfTheTrustedHopsIsIgnored() {
        // el cliente mandó "1.2.3.4" y un proxy de confianza agregó la IP real a la derecha
        assertEquals("203.0.113.7", resolver.resolve(from("172.18.0.5", "1.2.3.4, 203.0.113.7")));
        assertEquals("203.0.113.7", resolver.resolve(from("172.18.0.5", "1.2.3.4", "203.0.113.7, 10.0.0.9")));
    }

    @Test
    void somethingThatIsNotAnIpStopsTheWalk() {
        assertEquals("172.18.0.5", resolver.resolve(from("172.18.0.5", "evil.example.com")));
        assertEquals("172.18.0.5", resolver.resolve(from("172.18.0.5", "999.1.1.1")));
    }

    @Test
    void withoutTheHeaderItIsTheConnection() {
        assertEquals("127.0.0.1", resolver.resolve(from("127.0.0.1")));
        assertEquals("0:0:0:0:0:0:0:1", resolver.resolve(from("0:0:0:0:0:0:0:1")));
    }

    @Test
    void theTrustedNetworksCanBeConfigured() {
        ClientIpResolver onlyCaddy = new ClientIpResolver("10.1.0.0/16");

        assertEquals("203.0.113.7", onlyCaddy.resolve(from("10.1.2.3", "203.0.113.7")));
        assertEquals("172.18.0.5", onlyCaddy.resolve(from("172.18.0.5", "203.0.113.7")));
    }

    @Test
    void anInvalidNetworkStopsTheStartup() {
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("10.0.0.0/40"));
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("caddy"));
    }
}
