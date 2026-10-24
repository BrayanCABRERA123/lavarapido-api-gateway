package com.lavarapido.gateway;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * La IP real de quien llama. En producción la conexión llega desde el contenedor de Caddy, así que la
 * IP del cliente viene en X-Forwarded-For (Caddy la escribe y descarta la que mande el cliente).
 *
 * Ese encabezado solo se cree si la conexión viene de un proxy de confianza (TRUSTED_PROXIES, redes
 * en notación CIDR); si no, lo puede escribir cualquiera y se usa la IP de la conexión. Se recorre de
 * derecha a izquierda saltando los proxies de confianza: la primera IP que no lo es, es el cliente.
 */
final class ClientIpResolver {

    /** loopback y las redes privadas de Docker: ahí viven Caddy y los contenedores */
    static final String DEFAULT_TRUSTED_PROXIES =
            "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7";

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    // solo direcciones literales: así InetAddress nunca hace una consulta DNS con lo que mande el cliente.
    // Con ":" y un primer carácter hexadecimal o ":", Java lo lee como IPv6 o falla, sin consultar.
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("(?=.*:)[0-9a-fA-F:][0-9a-fA-F:.]*");

    private record Network(byte[] address, int prefixBits) {

        boolean contains(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != address.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != address[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (bytes[fullBytes] & mask) == (address[fullBytes] & mask);
        }
    }

    private final List<Network> trustedProxies;

    ClientIpResolver(String trustedProxies) {
        List<Network> networks = new ArrayList<>();
        for (String cidr : trustedProxies == null ? new String[0] : trustedProxies.split(",")) {
            if (!cidr.isBlank()) {
                networks.add(parseNetwork(cidr.strip()));
            }
        }
        this.trustedProxies = List.copyOf(networks);
    }

    String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        Optional<InetAddress> remoteAddress = parseAddress(remote);
        if (remoteAddress.isEmpty() || !isTrusted(remoteAddress.get())) {
            return remoteAddress.map(InetAddress::getHostAddress).orElse(remote);
        }
        String client = remoteAddress.get().getHostAddress();
        List<String> hops = forwardedFor(request);
        for (int i = hops.size() - 1; i >= 0; i--) {
            Optional<InetAddress> hop = parseAddress(hops.get(i));
            if (hop.isEmpty()) {
                // algo que no es una IP: lo que esté más a la izquierda ya no es confiable
                break;
            }
            client = hop.get().getHostAddress();
            if (!isTrusted(hop.get())) {
                break;
            }
        }
        return client;
    }

    private boolean isTrusted(InetAddress address) {
        return trustedProxies.stream().anyMatch(network -> network.contains(address));
    }

    private static List<String> forwardedFor(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders(X_FORWARDED_FOR))) {
            for (String hop : header.split(",")) {
                if (!hop.isBlank()) {
                    hops.add(hop.strip());
                }
            }
        }
        return hops;
    }

    private static Optional<InetAddress> parseAddress(String value) {
        try {
            if (value != null && IPV4.matcher(value).matches()) {
                String[] octets = value.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(octets[i]);
                    if (octet > 255) {
                        return Optional.empty();
                    }
                    bytes[i] = (byte) octet;
                }
                return Optional.of(InetAddress.getByAddress(bytes));
            }
            if (value != null && IPV6.matcher(value).matches()) {
                return Optional.of(InetAddress.getByName(value));
            }
        } catch (UnknownHostException notAnAddress) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Network parseNetwork(String cidr) {
        String[] parts = cidr.split("/", 2);
        InetAddress address = parseAddress(parts[0])
                .orElseThrow(() -> new IllegalArgumentException("TRUSTED_PROXIES: invalid network " + cidr));
        int maxBits = address.getAddress().length * 8;
        int bits;
        try {
            bits = parts.length == 2 ? Integer.parseInt(parts[1]) : maxBits;
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("TRUSTED_PROXIES: invalid prefix in " + cidr);
        }
        if (bits < 0 || bits > maxBits) {
            throw new IllegalArgumentException("TRUSTED_PROXIES: invalid prefix in " + cidr);
        }
        return new Network(address.getAddress(), bits);
    }
}
