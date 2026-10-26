package com.lavarapido.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SEC-04: tope de peticiones por IP a /api/v1/auth/** (login, registro y recuperar la contraseña). Sin
 * esto cualquiera podía bloquear cuentas en bucle (5 contraseñas erradas bloquean 15 minutos), probar
 * qué correos existen o llenar de códigos el correo de alguien.
 *
 * Cada IP tiene una cubeta de {@code requests} peticiones que se recarga completa en {@code window}.
 * Sin peticiones disponibles responde 429 RATE_LIMITED (ProblemDetail) con Retry-After, antes de llegar
 * a security-service. Vive en memoria: con varias réplicas del gateway cada una cuenta por su lado.
 */
class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    private static final String AUTH_PREFIX = "/api/v1/auth";
    private static final int MAX_TRACKED_CLIENTS = 10_000;
    private static final byte[] PROBLEM = ("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
            + "\"detail\":\"Too many requests from this address: try again in a moment\",\"code\":\"RATE_LIMITED\"}")
            .getBytes(StandardCharsets.UTF_8);

    private final ClientIpResolver clientIp;
    private final int requests;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Peticiones que le quedan a una IP; se recargan de a poco hasta {@code requests}. */
    private record Bucket(double tokens, Instant updatedAt) {
    }

    AuthRateLimitFilter(ClientIpResolver clientIp, int requests, Duration window, Clock clock) {
        if (requests < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("AUTH_RATE_LIMIT_REQUESTS and AUTH_RATE_LIMIT_WINDOW must be positive");
        }
        this.clientIp = clientIp;
        this.requests = requests;
        this.window = window;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // la ruta ya decodificada y sin "//" ni "..": "/api/v1/%61uth/login" también es el login
        String path = StringUtils.cleanPath(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
        // cleanPath devuelve null si no hay ruta: sin ruta no es una petición a /auth
        boolean isAuthPath = path != null && (path.equals(AUTH_PREFIX) || path.startsWith(AUTH_PREFIX + "/"));
        return "OPTIONS".equals(request.getMethod()) || !isAuthPath;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientIp.resolve(request);
        long retryAfterSeconds = take(client, clock.instant());
        if (retryAfterSeconds == 0) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Demasiadas peticiones a {} desde {}: se responde 429", AUTH_PREFIX, client);
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType("application/problem+json");
        response.getOutputStream().write(PROBLEM);
    }

    /** @return 0 si la petición puede seguir; si no, los segundos que faltan para tener otra */
    private long take(String client, Instant now) {
        makeRoomIfFull(now);
        long[] wait = {0};
        buckets.compute(client, (key, bucket) -> {
            Bucket current = bucket == null ? new Bucket(requests, now) : refilled(bucket, now);
            if (current.tokens() >= 1) {
                return new Bucket(current.tokens() - 1, now);
            }
            double missingNanos = (1 - current.tokens()) * window.toNanos() / requests;
            wait[0] = Math.max(1, (long) Math.ceil(missingNanos / 1_000_000_000d));
            return current;
        });
        return wait[0];
    }

    private Bucket refilled(Bucket bucket, Instant now) {
        long elapsedNanos = Math.max(0, Duration.between(bucket.updatedAt(), now).toNanos());
        double tokens = bucket.tokens() + (double) elapsedNanos * requests / window.toNanos();
        return new Bucket(Math.min(requests, tokens), now);
    }

    private void makeRoomIfFull(Instant now) {
        if (buckets.size() < MAX_TRACKED_CLIENTS) {
            return;
        }
        // una cubeta que ya se recargó del todo es igual a no tener cubeta
        buckets.values().removeIf(bucket -> refilled(bucket, now).tokens() >= requests);
        if (buckets.size() >= MAX_TRACKED_CLIENTS) {
            buckets.clear();
        }
    }
}
