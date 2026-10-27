# Changelog

Todos los cambios relevantes del proyecto se registran en este archivo.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el proyecto usa
[versionamiento semántico](https://semver.org/lang/es/).

## [1.3.0] - Unreleased

### Added

- Infraestructura en AWS con Terraform (`deployment/terraform/`): módulos `network`, `data`,
  `messaging`, `ecs-cluster`, `alb`, `ecs-service`, `edge`, `observability` y `platform`, y los
  ambientes `dev`, `qa` y `prod` con estado remoto en S3 (cifrado, versionado y con lock).
  - VPC en 3 zonas sin NAT, con 7 VPC endpoints.
  - Las 7 tablas con sus índices, TTL, PITR y KMS, y la cola con DLQ (`maxReceiveCount` 5).
  - API y worker en ECS Fargate con roles de mínimo privilegio y escalado propio (worker en Spot).
  - CloudFront con WAF, API Gateway con VPC Link y la ruta SSE directa al ALB interno.
  - Alarmas, dashboard y presupuesto por ambiente.
  - Se valida sin cuenta de AWS con `fmt`, `validate`, tflint, checkov y `terraform test`.
- CI en GitHub Actions: `build.yml` (build de Gradle con Testcontainers y reporte de cobertura) y
  `terraform.yml` (validación completa de la infraestructura).

### Changed

- `verifyEnvironmentParity` compara también las variables de `application.yaml` con las de la task
  definition de Terraform, en las dos direcciones.
- RFC §9: el barrido se despliega en el worker con su lock; EventBridge Scheduler queda como
  evolución.

## [1.2.1] - Unreleased

### Added

- Modos de demostración del simulador de pagos: `RANDOM` aprueba con la probabilidad
  `PAYMENT_SIMULATOR_APPROVAL_RATE` y decide solo por el `orderId`, así que repite la respuesta tras
  un reintento o un reinicio; `SLOW` responde después de `PAYMENT_SIMULATOR_SLOW_LATENCY`, mayor que
  el timeout del cobro; `DOWN` falla todos los cobros. `SLOW` y `DOWN` responden `TKT0007` y muestran
  los reintentos, la DLQ y el vencimiento con reembolso.

### Changed

- Un error inesperado del simulador de pagos responde `TKT0009` en lugar de `TKT0007`.
- README: modos del simulador y la demora propia de SQS al recrear el worker con un mensaje en vuelo.

## [1.2.0] - Unreleased

### Added

- RF6, liberación automática: el worker barre cada 30 s (`SWEEP_INTERVAL`) las reservas `RESERVED` y
  `PENDING_CONFIRMATION` vencidas por el índice disperso `expiring-reservations`, las deja en
  `EXPIRED`, devuelve sus entradas al inventario y al límite del cliente, y pide el reembolso de las
  que esperaban el pago. Una sola réplica barre en cada ciclo gracias a un lock con lease en la tabla
  `scheduler_locks`; cada liberación sigue siendo condicional, así que nunca mueve el inventario dos
  veces. Métrica `ticketing.orders.sweep.released`.
- Entradas de cortesía: `POST /events/{eventId}/complimentary` asigna de 1 a 50 entradas
  (`COMPLIMENTARY_MAX_PER_GRANT`) de un evento a la venta en una orden final `COMPLIMENTARY`, en una
  sola transacción que las mueve de disponibles a cortesía. No cuentan para el límite del cliente.
- Colección de Postman con los 6 flujos del RFC y su entorno local (`postman/`).

### Changed

- README final con arquitectura, instalación, configuración, ejemplos, decisiones y limitaciones.
- RFC público actualizado: lock del barrido con escritura condicional en lugar de ShedLock, outbox
  liviano, idempotencia en dos fases y estado de implementación por feature.

## [1.1.0] - Unreleased

### Added

- RF2, reserva temporal: `POST /orders` reserva de 1 a 6 entradas por 10 minutos en una sola
  transacción condicional de DynamoDB y responde 202 sin esperar el pago, con un límite de 6
  entradas por cliente y evento.
- RF3, procesamiento asíncrono: la API publica `OrderPlaced` en SQS y el worker la procesa por el
  estado actual de la orden, cobra con un simulador idempotente y confirma la venta solo si la
  reserva sigue vigente. Cola con DLQ, republicación de las órdenes no publicadas y reembolso de un
  pago tardío.
- RF4, consulta de la orden: `GET /orders/{orderId}` con el estado efectivo y el de cada entrada,
  solo para su dueño (`X-Customer-Id`).
- RF5, control de concurrencia: cero sobreventa con escrituras condicionales atómicas, idempotencia
  por `Idempotency-Key` en dos fases y auditoría de cada cambio de estado.
- Despliegue `worker` en Docker Compose, métricas `ticketing.orders.outcomes` y
  `ticketing.orders.messages.dead_lettered`.

### Fixed

- Una capacidad o cantidad decimal ya no se trunca a entero: responde `TKT0001` nombrando el campo.

## [1.0.0] - Unreleased

### Added

- RF1, gestión de eventos: crear un evento con su inventario en una sola transacción
  (`POST /events`), consultarlo por id (`GET /events/{eventId}`) y listar los activos con fecha
  futura, paginados por cursor (`GET /events`).
- RF7, disponibilidad: consulta individual con lectura fuerte (`GET /events/{eventId}/availability`),
  por lote de hasta 100 eventos (`GET /events/availability?ids=`) y en vivo por SSE, con un sondeo
  compartido por evento, keepalive y reconexión (`GET /events/{eventId}/availability/stream`).
- Inventario repartido en porciones (1 a 20 por evento) para repartir las escrituras concurrentes.
- Catálogo de errores con códigos estables (`TKB`, `TKT`) y manejador global de errores.
- Entorno local con Docker Compose y LocalStack 4.14.0 (DynamoDB, SQS y Secrets Manager).
- Calidad: cobertura combinada ≥ 90 %, pruebas de mutación, prueba de latencia, escaneo con Trivy y
  análisis con SonarQube local.
