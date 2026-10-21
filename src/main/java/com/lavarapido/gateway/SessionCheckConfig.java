package com.lavarapido.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

/**
 * Registra el chequeo de sesión justo después del CORS: así el 401 de una sesión cerrada sale con
 * sus encabezados CORS y el navegador lo puede leer (sin ellos lo vería como un error de red).
 */
@Configuration
@ConditionalOnProperty(name = "app.session-check.enabled", havingValue = "true", matchIfMissing = true)
class SessionCheckConfig {

    @Bean
    FilterRegistrationBean<SessionCheckFilter> sessionCheckFilter(
            @Value("${ROUTE_SECURITY_URL:http://localhost:3001}") String securityUrl,
            @Value("${app.session-check.cache-ttl:30s}") Duration cacheTtl,
            @Value("${app.session-check.timeout:3s}") Duration timeout) {
        SessionCheckFilter filter = new SessionCheckFilter(URI.create(securityUrl + "/api/v1/auth/session"),
                cacheTtl, timeout, Clock.systemUTC());
        FilterRegistrationBean<SessionCheckFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
