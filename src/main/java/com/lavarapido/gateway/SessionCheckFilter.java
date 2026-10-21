package com.lavarapido.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Corta los tokens de sesiones ya cerradas (logout, cuenta inhabilitada o desactivada) antes de que
 * lleguen a cualquier servicio. Cada servicio solo verifica la firma y la vigencia del JWT (ADR-006),
 * así que sin esto un token cerrado seguía sirviendo hasta su exp (1 hora).
 *
 * Pregunta a security-service (GET /api/v1/auth/session con el mismo Authorization) y guarda la
 * respuesta unos segundos por token, para no hacer una llamada extra en cada petición. Si
 * security-service no contesta, deja pasar: el servicio de atrás igual verifica la firma, y una caída
 * de security no tumba el resto de la API.
 */
class SessionCheckFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionCheckFilter.class);

    private static final String BEARER = "Bearer ";
    // login, logout y el propio chequeo los resuelve security-service
    private static final String AUTH_PATHS = "/api/v1/auth/";
    private static final int MAX_CACHED_TOKENS = 10_000;

    private final URI sessionUri;
    private final Duration cacheTtl;
    private final Duration timeout;
    private final HttpClient client;
    private final Clock clock;
    private final Map<String, Checked> cache = new ConcurrentHashMap<>();

    /** Respuesta guardada de security-service: {@code closed} trae el 401 tal cual para reenviarlo. */
    private record Checked(Instant until, Rejection closed) {
    }

    private record Rejection(String contentType, String wwwAuthenticate, byte[] body) {
    }

    SessionCheckFilter(URI sessionUri, Duration cacheTtl, Duration timeout, Clock clock) {
        this.sessionUri = sessionUri;
        this.cacheTtl = cacheTtl;
        this.timeout = timeout;
        this.clock = clock;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return "OPTIONS".equals(request.getMethod())
                || !path.startsWith("/api/")
                || path.startsWith(AUTH_PATHS)
                || authorization == null || !authorization.startsWith(BEARER);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        Rejection closed = check(authorization, request.getHeader("X-Correlation-Id"));
        if (closed == null) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        if (closed.contentType() != null) {
            response.setContentType(closed.contentType());
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, closed.wwwAuthenticate() != null ? closed.wwwAuthenticate() : "Bearer");
        response.getOutputStream().write(closed.body());
    }

    /** @return el 401 que hay que devolver, o null si la petición puede seguir */
    private Rejection check(String authorization, String correlationId) {
        String key = sha256(authorization);
        Instant now = clock.instant();
        Checked cached = cache.get(key);
        if (cached != null && cached.until().isAfter(now)) {
            return cached.closed();
        }

        HttpRequest.Builder ask = HttpRequest.newBuilder(sessionUri).timeout(timeout).GET()
                .header(HttpHeaders.AUTHORIZATION, authorization);
        if (correlationId != null) {
            ask.header("X-Correlation-Id", correlationId);
        }
        HttpResponse<byte[]> answer;
        try {
            answer = client.send(ask.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException unreachable) {
            log.warn("No se pudo confirmar la sesión con security-service ({}); la petición sigue", unreachable.toString());
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }

        Rejection closed;
        if (answer.statusCode() / 100 == 2) {
            closed = null;
        } else if (answer.statusCode() == HttpServletResponse.SC_UNAUTHORIZED) {
            closed = new Rejection(answer.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(null),
                    answer.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse(null), answer.body());
        } else {
            // 5xx u otra respuesta rara: no se decide nada ni se guarda
            log.warn("security-service respondió {} al confirmar la sesión; la petición sigue", answer.statusCode());
            return null;
        }
        remember(key, new Checked(now.plus(cacheTtl), closed), now);
        return closed;
    }

    private void remember(String key, Checked checked, Instant now) {
        if (cache.size() >= MAX_CACHED_TOKENS) {
            cache.values().removeIf(entry -> !entry.until().isAfter(now));
            if (cache.size() >= MAX_CACHED_TOKENS) {
                cache.clear();
            }
        }
        cache.put(key, checked);
    }

    // el token no se guarda en memoria tal cual, solo su huella
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
