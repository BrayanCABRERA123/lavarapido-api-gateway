package com.lavarapido.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada único del sistema (ADR-005): recibe todas las peticiones de la web y del
 * móvil en el puerto 8080 y las reenvía al microservicio dueño de cada ruta.
 *
 * No tiene lógica de negocio ni valida el JWT: cada servicio verifica su token (ADR-006).
 * Las rutas están en application.yml.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
