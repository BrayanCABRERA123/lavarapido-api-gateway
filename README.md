# api-gateway

Punto de entrada único del sistema (ADR-005). La web y el móvil llaman a **un solo puerto
(8080)** y el gateway reenvía cada petición al microservicio dueño de la ruta.

Stack: Spring Boot 4 + Spring Cloud Gateway (variante webmvc) · Java 21.

## Qué hace y qué no

- **Sí:** enrutar `/api/v1/**` al servicio correcto, pasando la petición tal cual (método,
  cuerpo, parámetros y encabezados como `Authorization` y `X-Correlation-Id`).
- **No:** lógica de negocio, ni validar el JWT, ni CORS. Cada servicio verifica su token
  (ADR-006) y responde su propio CORS; el gateway no los duplica.
- **No expone** `/internal/**` (llamadas entre servicios, ADR-011 §8): desde afuera es 404.

## Rutas

| Ruta | Servicio |
|---|---|
| `/api/v1/auth/**`, `/api/v1/users/**`, `/api/v1/admin/users/**` | security-service (3001) |
| `/api/v1/vehicles/**`, `/api/v1/vehicle-types`, `/api/v1/admin/vehicles` | customer-service (3002) |
| `/api/v1/catalog/**`, `/api/v1/schedule/**`, `/api/v1/establishment`, `/api/v1/bookings/**`, `/api/v1/admin/bookings/**`, `/api/v1/admin/catalog/**`, `/api/v1/admin/schedule/**`, `/api/v1/admin/bays/**` | booking-service (3003) |
| `/api/v1/notifications/**`, `/api/v1/admin/notifications/**` | notification-service (3006) |

Son las mismas que `Web/proxy.conf.json` usa en desarrollo. Al agregar un servicio o una ruta
nueva hay que actualizar los dos sitios.

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
| `ROUTE_NOTIFICATION_URL` | `http://localhost:3006` |

En Docker (`docker compose --profile app up`) las URLs apuntan al nombre de cada contenedor.

## Pruebas

```bash
./mvnw test
```

`GatewayRoutesTest` levanta un servidor falso por servicio y comprueba que cada ruta llegue al
correcto, que viajen el token y los parámetros, y que `/internal/**` no esté expuesto.
