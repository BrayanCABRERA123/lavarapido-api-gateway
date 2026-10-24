package com.lavarapido.gateway;

import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.List;
import java.util.Map;

/**
 * SEC-11: los servicios guardan la IP del cliente (sesiones y códigos de recuperación). Sin esto veían la
 * del gateway: Spring Cloud Gateway quita los X-Forwarded-* que llegan (no tiene trusted-proxies) y no
 * pone ninguno. Aquí se manda X-Forwarded-For con la IP que resolvió ClientIpResolver, nunca la que
 * escribió el cliente. Va de último, después del filtro del gateway que borra esos encabezados.
 */
class ClientIpHeadersFilter implements HttpHeadersFilter.RequestHttpHeadersFilter, Ordered {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final ClientIpResolver clientIp;

    ClientIpHeadersFilter(ClientIpResolver clientIp) {
        this.clientIp = clientIp;
    }

    @Override
    public HttpHeaders apply(HttpHeaders input, ServerRequest request) {
        HttpHeaders headers = new HttpHeaders();
        for (Map.Entry<String, List<String>> header : input.headerSet()) {
            headers.addAll(header.getKey(), header.getValue());
        }
        headers.set(X_FORWARDED_FOR, clientIp.resolve(request.servletRequest()));
        return headers;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
