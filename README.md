# api-gateway

Punto de entrada único del sistema (ADR-005). La web y el móvil llaman a **un solo puerto
(8080)** y el gateway reenvía cada petición al microservicio dueño de la ruta.

Stack: Spring Boot 4 + Spring Cloud Gateway (variante webmvc) · Java 21.

## Qué hace y qué no

- **Sí:** enrutar `/api/v1/**` al servicio correcto, pasando la petición tal cual (método,
  cuerpo, parámetros y encabezados como `Authorization` y `X-Correlation-Id`).
- **Sí, CORS** (`CorsConfig`): el navegador manda primero un preflight (`OPTIONS`) y el gateway lo
  responde él mismo, así que el CORS tiene que estar aquí. Usa los mismos orígenes que los servicios
  (`CORS_ALLOWED_ORIGINS`) y cada ruta quita los encabezados CORS que devuelve el servicio, porque un
  `Access-Control-Allow-Origin` repetido también lo rechaza el navegador.
- **Sí, cortar tokens de sesiones cerradas** (`SessionCheckFilter`, desde el 21 de octubre): antes de
  pasar a cualquier servicio una llamada que traiga `Authorization`, con cualquier esquema
  (`Bearer`, `bearer`, `BEARER`...) y en cualquier ruta (SEC-01), le pregunta a security-service
  (`GET /api/v1/auth/session`) si la sesión sigue abierta. Si se cerró (logout, cuenta inhabilitada o
  desactivada, rol retirado) responde el mismo 401 de security. Las rutas `/api/v1/auth/**` (salvo el
  logout) no se revisan aquí: las atiende security, que revisa la sesión él mismo.
  - La respuesta se guarda 30 segundos por token; la llave es solo el token, sin el esquema, así que
    un logout que pasa por aquí la borra al instante para todas sus variantes.
  - **Riesgo aceptado (D-02):** un cierre hecho por otro camino (admin inhabilita, cambio de
    contraseña, rol retirado) tarda hasta 30 s en notarse en los demás servicios, y si security no
    contesta (caído o más de 3 s) la llamada sigue (*fail-open*): los servicios igual verifican la
    firma y el vencimiento del JWT (1 hora). Se eligió así para que una caída de security no tumbe toda
    la API; security-service siempre revisa la sesión en sus propias rutas.
- **Sí, límite de peticiones a `/api/v1/auth/**`** (`AuthRateLimitFilter`, SEC-04): cada IP puede
  hacer 60 peticiones seguidas (login, registro, recuperar contraseña, logout) que se le recargan en un
  minuto; después responde `429` con `code: RATE_LIMITED` (ProblemDetail), `Retry-After` y CORS, sin
  llegar a security. La ruta se revisa decodificada (`/api/v1/%61uth/login` también cuenta). Vive en
  memoria: con varias réplicas cada una cuenta por su lado.
- **Sí, la IP real del cliente** (`ClientIpResolver`, SEC-04 y SEC-11): `X-Forwarded-For` solo se
  cree si la conexión viene de un proxy de confianza (`TRUSTED_PROXIES`: loopback y redes privadas de
  Docker, donde vive Caddy, que escribe ese encabezado y descarta el que mande el cliente). Esa IP se
  usa para el límite y se manda a los servicios en `X-Forwarded-For` (`ClientIpHeadersFilter`); security
  la guarda en las sesiones y los códigos de recuperación.
- **Sí, tiempos de espera hacia los servicios** (SEC-16): 5 s para conectar y 60 s para responder
  (alcanza para subir fotos); si se pasan, el gateway responde 500 y libera el hilo.
- **No:** lógica de negocio ni validar la firma del JWT: cada servicio verifica su token (ADR-006).
- **No expone** `/internal/**` (llamadas entre servicios, ADR-011 §8): desde afuera es 404.
- **No expone** `/api/v1/operator/bookings/**` de booking-service: solo operations-service la usa, por la red interna y con la llave interna (E-01). El operario inicia y termina servicios en `/api/v1/operator/services/**`.

## Rutas

| Ruta | Servicio |
|---|---|
| `/api/v1/auth/**`, `/api/v1/users/**`, `/api/v1/admin/users/**`, `/api/v1/admin/roles-permissions/**` | security-service (3001) |
| `/api/v1/vehicles/**`, `/api/v1/vehicle-types/**`, `/api/v1/admin/vehicles/**` | customer-service (3002) |
| `/api/v1/catalog/**`, `/api/v1/schedule/**`, `/api/v1/establishment`, `/api/v1/bookings/**`, `/api/v1/admin/bookings/**`, `/api/v1/admin/catalog/**`, `/api/v1/admin/schedule/**`, `/api/v1/admin/bays/**`, `/api/v1/admin/establishment` | booking-service (3003) |
| `/api/v1/admin/operators/**`, `/api/v1/operators/**`, `/api/v1/admin/inspections/**`, `/api/v1/public/inspections/**`, `/api/v1/admin/assignments/**`, `/api/v1/operator/services/**`, `/api/v1/operator/ratings`, `/api/v1/ratings/**` | operations-service (3004) |
| `/api/v1/payments/**`, `/api/v1/payment-accounts`, `/api/v1/admin/payments/**`, `/api/v1/admin/payment-accounts/**`, `/api/v1/admin/payment-methods`, `/api/v1/admin/promotions/**`, `/api/v1/loyalty/**` | payment-service (3005) |
| `/api/v1/notifications/**`, `/api/v1/admin/notifications/**` | notification-service (3006) |

La lista exacta está en `src/main/resources/application.yml`. En desarrollo la web manda todo `/api`
al gateway (`proxy.conf.json`), así que una ruta nueva solo se agrega aquí.

## Cómo correrlo

```bash
./mvnw spring-boot:run        # puerto 8080
curl http://localhost:8080/actuator/health
```

| Variable | Por defecto |
|---|---|
| `GATEWAY_PORT` | `8080` |
| `ROUTE_SECURITY_URL` | `http://localhost:3001` |
| `ROUTE_CUSTOMER_URL` | `http://localhost:3002` |
| `ROUTE_BOOKING_URL` | `http://localhost:3003` |
| `ROUTE_OPERATIONS_URL` | `http://localhost:3004` |
| `ROUTE_PAYMENT_URL` | `http://localhost:3005` |
| `ROUTE_NOTIFICATION_URL` | `http://localhost:3006` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://localhost:8081` |
| `SESSION_CHECK_ENABLED` | `true` |
| `SESSION_CHECK_CACHE_TTL` | `30s` (cuánto se guarda la respuesta sobre la sesión de un token) |
| `SESSION_CHECK_TIMEOUT` | `3s` (cuánto espera a security antes de dejar seguir la llamada) |
| `AUTH_RATE_LIMIT_ENABLED` | `true` (límite por IP en `/api/v1/auth/**`) |
| `AUTH_RATE_LIMIT_REQUESTS` | `60` (peticiones seguidas por IP) |
| `AUTH_RATE_LIMIT_WINDOW` | `1m` (en cuánto se recargan) |
| `TRUSTED_PROXIES` | `127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7` (redes CIDR de los proxies cuyo `X-Forwarded-For` se cree) |
| `ROUTE_CONNECT_TIMEOUT` | `5s` (conectar con un servicio) |
| `ROUTE_READ_TIMEOUT` | `60s` (esperar la respuesta de un servicio) |

En Docker (`docker compose --profile app up`) las URLs apuntan al nombre de cada contenedor.

## Pruebas

```bash
./mvnw test
```

62 pruebas con las ramas del 23 de octubre de 2026 (SEC-01, SEC-04, SEC-11 y SEC-16) fusionadas; develop sin ellas tiene 39 (24 de octubre de 2026).
- `GatewayRoutesTest` levanta un servidor falso por servicio y comprueba que cada ruta llegue al
  correcto, que viajen el token, los parámetros y la IP del cliente, que `/internal/**` y
  `/api/v1/operator/bookings/**` no estén expuestos y que el CORS salga una sola vez.
- `SessionCheckFilterTest` usa un security falso: una sesión abierta pasa, una cerrada recibe el 401
  de security con cualquier esquema y en cualquier ruta, la respuesta se guarda por token, el logout
  la borra para todas las variantes y si security falla la llamada sigue.
- `AuthRateLimitFilterTest`, `ClientIpResolverTest` y `GatewayAuthRateLimitTest`: el 429 tras el
  tope, por IP, con recarga, rutas codificadas, y que el `X-Forwarded-For` de un cliente directo no
  sirva para cambiar de IP.
- `GatewayRouteTimeoutTest`: un servicio que no contesta se corta al tiempo de espera.

## Proyecto LavaRápido

Este repositorio es parte de LavaRápido (Vehicle Washing), proyecto formativo del Tecnólogo en
Análisis y Desarrollo de Software (ADSO), ficha 3145556, SENA Regional Huila.

| Recurso | Enlace |
|---|---|
| Documentación y manuales (ES/EN, MD · PDF · Word) | [lavarapido-docs](https://github.com/BrayanCABRERA123/lavarapido-docs#readme) |
| Manual técnico | [09_MANUALES/ES/MD/manual-tecnico.md](https://github.com/BrayanCABRERA123/lavarapido-docs/blob/main/09_MANUALES/ES/MD/manual-tecnico.md) |
| Manual de instalación | [09_MANUALES/ES/MD/manual-de-instalacion.md](https://github.com/BrayanCABRERA123/lavarapido-docs/blob/main/09_MANUALES/ES/MD/manual-de-instalacion.md) |
| Levantar todo el sistema | [lavarapido-infra](https://github.com/BrayanCABRERA123/lavarapido-infra) (`docker compose --profile app up -d`) |
| Aplicación desplegada | https://lavarapidovehicular.northcentralus.cloudapp.azure.com |

**Ramas y commits:** se trabaja en `develop` con ramas `feature/`, `fix/` y `docs/` que se
fusionan con `git merge --no-ff`; los commits siguen Conventional Commits
(`feat(scope): ...`, `fix(scope): ...`). Detalle en la
[guía de git del equipo](https://github.com/BrayanCABRERA123/lavarapido-docs#guía-de-git-del-equipo).

**Equipo:** Brayan Estiven Patiño Cabrera, Juan Camilo Guilombo Osorio y Merari Urbano Quintero.
