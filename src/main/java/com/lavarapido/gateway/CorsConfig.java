package com.lavarapido.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.time.Duration;
import java.util.List;

/**
 * CORS en el gateway. Hace falta aquí porque el navegador manda primero un preflight (OPTIONS)
 * y el gateway lo responde él mismo: si no tuviera CORS, el navegador bloquearía la llamada
 * aunque el servicio de atrás sí lo tenga.
 *
 * Mismos orígenes que los servicios (CORS_ALLOWED_ORIGINS). Las rutas quitan los encabezados
 * CORS que devuelve cada servicio (application.yml), porque un Access-Control-Allow-Origin
 * repetido también hace que el navegador rechace la respuesta.
 */
@Configuration
class CorsConfig {

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language", "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("X-Correlation-Id"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
