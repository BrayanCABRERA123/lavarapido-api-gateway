package com.lavarapido.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;
import java.time.Duration;

/**
 * Registra el tope de peticiones a /api/v1/auth/** (SEC-04) después del CORS, para que el 429 salga con
 * sus encabezados CORS y la web lo pueda leer, y antes del chequeo de sesión.
 */
@Configuration
class AuthRateLimitConfig {

    /** La IP real del cliente detrás de Caddy; solo se cree X-Forwarded-For de TRUSTED_PROXIES. */
    @Bean
    ClientIpResolver clientIpResolver(@Value("${app.trusted-proxies:" + ClientIpResolver.DEFAULT_TRUSTED_PROXIES + "}")
                                      String trustedProxies) {
        return new ClientIpResolver(trustedProxies);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.auth.enabled", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<AuthRateLimitFilter> authRateLimitFilter(ClientIpResolver clientIp,
            @Value("${app.rate-limit.auth.requests:60}") int requests,
            @Value("${app.rate-limit.auth.window:1m}") Duration window) {
        FilterRegistrationBean<AuthRateLimitFilter> registration = new FilterRegistrationBean<>(
                new AuthRateLimitFilter(clientIp, requests, window, Clock.systemUTC()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }
}
