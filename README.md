# Ticketing Platform

Backend reactivo de venta de entradas para eventos. Expone una API REST para crear eventos,
consultar su disponibilidad (también en vivo), comprar entradas y consultar las órdenes. Garantiza
**cero sobreventa** con escrituras condicionales atómicas en DynamoDB, acepta la compra en
milisegundos sin esperar el pago y procesa las órdenes de forma asíncrona con SQS. Las reservas
vencidas vuelven solas al inventario y un administrador puede asignar entradas de cortesía.

El diseño completo, con sus alternativas y diagramas, está en el
[RFC-001](docs/rfc/RFC-001-ticketing.md).

## Contenido

1. [Arquitectura](#arquitectura)
2. [Requisitos](#requisitos)
3. [Instalación y ejecución con Docker](#instalación-y-ejecución-con-docker)
4. [Configuración](#configuración)
5. [API y ejemplos](#api-y-ejemplos)
6. [Colección de Postman](#colección-de-postman)
7. [Pruebas y calidad](#pruebas-y-calidad)
8. [Decisiones clave](#decisiones-clave)
9. [Limitaciones conocidas y qué cambiaría en producción](#limitaciones-conocidas-y-qué-cambiaría-en-producción)
10. [Hoja de ruta](#hoja-de-ruta)

## Arquitectura

Una sola imagen con dos roles, elegidos por la variable `APP_ROLE`:

- **`api`** (puerto 8080): rutas REST y SSE. Reserva las entradas en la misma transacción que crea
  la orden, responde `202` y publica `OrderPlaced` en SQS.
- **`worker`** (puerto 8081): consume la cola, cobra con el simulador de pagos y deja la orden en
  `SOLD`, `CANCELLED` o `EXPIRED`. Además corre dos tareas periódicas: la republicación de las
  órdenes que no se alcanzaron a publicar y el **barrido de reservas vencidas**.

![Microservicios y colas](docs/rfc/diagrams/microservices-queues.png)

El código sigue Clean Architecture con el plugin `co.com.bancolombia.cleanArchitecture`:

| Capa | Módulos |
| --- | --- |
| Dominio | `domain/model` (agregados `Event` y `Order`, inventario por porciones, gateways) y `domain/usecase` (un caso de uso por clase) |
| Entry points | `reactive-web` (WebFlux con RouterFunctions y springdoc), `sqs-listener`, `scheduler` |
| Driven adapters | `dynamo-db`, `sqs-sender`, `payment-simulator` |
| Helpers | `commons` (excepciones técnicas), `sqs-commons` (contrato del mensaje), `metrics` |
| Aplicación | `applications/app-service` (configuración, arranque y pruebas de integración) |

Estados de la orden: `RESERVED → PENDING_CONFIRMATION → SOLD`, con salida a `CANCELLED` o `EXPIRED`
desde los estados activos. `COMPLIMENTARY` es un estado final con el que nace una orden de cortesía.

![Máquina de estados de la orden](docs/rfc/diagrams/state-machine.png)

Stack: Java 25, Spring Boot 4.1 con WebFlux, AWS SDK v2 asíncrono (DynamoDB y SQS), Gradle 9,
LocalStack 4.14 para el entorno local, JUnit 5, Mockito, reactor-test, BlockHound, Testcontainers,
JaCoCo y PIT.

## Requisitos

- Docker con Docker Compose (también lo usan las pruebas de integración, vía Testcontainers).
- JDK 25 (Temurin) solo para compilar o correr las pruebas fuera de Docker. Gradle se descarga con
  el wrapper.
- `curl` y, opcionalmente, `uuidgen` para los ejemplos.

## Instalación y ejecución con Docker

```bash
git clone <url-del-repositorio> && cd ticketing-platform/deployment
cp .env.example .env
docker compose up -d --build
```

`docker compose up` levanta:

| Servicio | Puerto | Qué hace |
| --- | --- | --- |
| `localstack` | 4566 | DynamoDB y SQS; los scripts de `localstack/init/ready.d/` crean las tablas y las colas |
| `ticketing-api` | 8080 | Rol `api` |
| `ticketing-worker` | 8081 | Rol `worker` |

Verificar que todo esté arriba y seguir los logs:

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8081/actuator/health
docker compose logs -f ticketing-api ticketing-worker
```

Apagar y borrar los datos:

```bash
docker compose down -v
```

La documentación OpenAPI queda en `http://localhost:8080/v3/api-docs`. Más detalle del entorno
local en [GETTINGSTARTED.md](GETTINGSTARTED.md).

## Configuración

Toda la configuración está en `applications/app-service/src/main/resources/application.yaml` y se
lee de variables de entorno **sin valores por defecto**: si falta una, la aplicación no arranca.
`deployment/.env.example` trae los valores locales y el build verifica que ambos archivos declaren las
mismas variables. Las más útiles para probar:

| Variable | Valor local | Para qué |
| --- | --- | --- |
| `APP_ROLE` | `api` / `worker` | Rol del proceso |
| `ORDERS_RESERVATION_TTL` | `PT10M` | Duración de una reserva |
| `ORDERS_MAX_TICKETS_PER_ORDER`, `ORDERS_MAX_TICKETS_PER_CUSTOMER` | `6`, `6` | Límites de compra |
| `PAYMENT_SIMULATOR_MODE` | `APPROVE` | `APPROVE`, `DECLINE`, `RANDOM`, `SLOW` o `DOWN` (ver [Modos del simulador de pagos](#modos-del-simulador-de-pagos)) |
| `PAYMENT_SIMULATOR_LATENCY` | `PT0.2S` | Latencia simulada del cobro |
| `PAYMENT_SIMULATOR_APPROVAL_RATE` | `0.5` | Probabilidad de aprobación en `RANDOM`, de 0 a 1 |
| `PAYMENT_SIMULATOR_SLOW_LATENCY` | `PT5S` | Demora del cobro en `SLOW`; debe superar `PAYMENT_TIMEOUT` |
| `SWEEP_INTERVAL` | `PT30S` | Cada cuánto corre el barrido de reservas vencidas |
| `SWEEP_LOCK_LEASE` | `PT60S` | Lease del lock del barrido entre réplicas |
| `SWEEP_BATCH_SIZE`, `SWEEP_CONCURRENCY` | `500`, `8` | Órdenes por ciclo y en paralelo |
| `COMPLIMENTARY_MAX_PER_GRANT` | `50` | Máximo de cortesías por asignación (tope absoluto 50) |
| `INVENTORY_SHARD_COUNT` | `1` | Porciones del inventario de un evento nuevo |

Las credenciales de AWS de `.env.example` son las ficticias de LocalStack; no hay secretos en el
código, los YAML ni la imagen.

## API y ejemplos

Base: `http://localhost:8080/api/v1`. Las respuestas exitosas usan `{"meta": {...}, "data": ...}` y
los errores `{"errors": [{"reason", "code", "message", "domain"}]}` con un código estable del
catálogo (`TKS` éxito, `TKB` negocio, `TKT` técnico).

| Método | Ruta | Respuesta |
| --- | --- | --- |
| `POST` | `/events` | 201, crea un evento con su inventario |
| `GET` | `/events?limit=&cursor=` | 200, eventos activos con fecha futura, paginados |
| `GET` | `/events/{eventId}` | 200, un evento |
| `GET` | `/events/{eventId}/availability` | 200, disponibilidad con lectura fuerte |
| `GET` | `/events/availability?ids=` | 200, disponibilidad de hasta 100 eventos |
| `GET` | `/events/{eventId}/availability/stream` | SSE, disponibilidad en vivo |
| `POST` | `/orders` | 202, reserva y acepta la orden (`X-Customer-Id`, `Idempotency-Key`) |
| `GET` | `/orders/{orderId}` | 200, la orden con el estado de cada entrada (solo su dueño) |
| `POST` | `/events/{eventId}/complimentary` | 201, entradas de cortesía (rol administrador desde la feature 004) |

```bash
BASE=http://localhost:8080/api/v1
CUSTOMER=11111111-1111-4111-8111-111111111111

# Crear un evento (201, TKS0002)
EVENT=$(curl -s -X POST $BASE/events -H 'Content-Type: application/json' \
  -d '{"name":"Rock al Parque 2027","venue":{"name":"Movistar Arena","city":"Bogota"},"date":"2027-03-20T01:00:00Z","capacity":500}' \
  | sed -E 's/.*"eventId":"([^"]+)".*/\1/')

# Consultar el evento y listar los activos (limit de 1 a 100; cursor opaco de data.nextCursor)
curl -s $BASE/events/$EVENT
curl -s "$BASE/events?limit=20"

# Disponibilidad: de un evento, de varios (los ids inexistentes se omiten) y en vivo
curl -s $BASE/events/$EVENT/availability
curl -s "$BASE/events/availability?ids=$EVENT"
curl -N $BASE/events/$EVENT/availability/stream

# Comprar de 1 a 6 entradas: reserva por 10 minutos y acepta la orden (202, TKS0003).
# Repetir con la misma Idempotency-Key y el mismo body devuelve exactamente la misma respuesta.
KEY=$(uuidgen)
ORDER=$(curl -s -X POST $BASE/orders -H 'Content-Type: application/json' \
  -H "X-Customer-Id: $CUSTOMER" -H "Idempotency-Key: $KEY" \
  -d "{\"eventId\":\"$EVENT\",\"quantity\":2}" | sed -E 's/.*"orderId":"([^"]+)".*/\1/')

# Consultar la orden propia: RESERVED, luego SOLD cuando el worker la procesa
curl -s $BASE/orders/$ORDER -H "X-Customer-Id: $CUSTOMER"

# Asignar 3 entradas de cortesía a un cliente (201, orden COMPLIMENTARY)
curl -s -X POST $BASE/events/$EVENT/complimentary -H 'Content-Type: application/json' \
  -d "{\"customerId\":\"$CUSTOMER\",\"quantity\":3}"
```

Errores frecuentes: `TKB0001` evento inexistente (404), `TKB0003` agotado (409), `TKB0004` límite
por cliente (422), `TKB0005` llave reutilizada con otro body (422), `TKB0007` evento no a la venta
(409), `TKT0001` validación con el nombre del campo (400).

El worker y la cola de mensajes fallidos se revisan así:

```bash
cd deployment
docker compose logs -f ticketing-worker
docker compose exec localstack awslocal sqs receive-message --message-attribute-names All \
  --queue-url http://localhost:4566/000000000000/order-placed-dlq
```

### Ver una reserva que vence

Para que ningún mensaje procese la orden y la libere el barrido, se recrean la API y el worker con un
archivo `override.yml` en `deployment/`:

```yaml
services:
  ticketing-api:
    environment:
      SQS_ORDER_PLACED_QUEUE: missing
      ORDERS_RESERVATION_TTL: PT20S
  ticketing-worker:
    environment:
      ORDERS_REPUBLISH_INTERVAL: PT10M
```

```bash
docker compose -f docker-compose.yml -f override.yml up -d --no-deps ticketing-api ticketing-worker
# comprar 2 entradas; a los 20 s la orden se ve EXPIRED y, tras el siguiente barrido (≤ 30 s),
# la disponibilidad recupera las 2 entradas
docker compose up -d --no-deps ticketing-api ticketing-worker   # restaurar
```

### Modos del simulador de pagos

El worker cobra contra un simulador que responde según `PAYMENT_SIMULATOR_MODE`. Cada modo muestra
un camino distinto del sistema; en todos, una misma orden cuenta a lo sumo un cobro y el reembolso es
idempotente.

| Modo | Qué hace el simulador | Qué se observa |
| --- | --- | --- |
| `APPROVE` | Aprueba todos los cobros | La orden pasa a `SOLD` |
| `DECLINE` | Rechaza todos los cobros | La orden pasa a `CANCELLED` y las entradas vuelven a estar disponibles |
| `RANDOM` | Aprueba con la probabilidad `PAYMENT_SIMULATOR_APPROVAL_RATE` | Cerca de esa fracción de órdenes termina `SOLD` y el resto `CANCELLED`. La decisión depende solo del `orderId`, así que un reintento o un reinicio del worker repite la misma respuesta |
| `SLOW` | Responde después de `PAYMENT_SIMULATOR_SLOW_LATENCY`, que supera `PAYMENT_TIMEOUT` | Cada intento falla por timeout con `TKT0007`, SQS reentrega el mensaje y al agotar los intentos pasa a la DLQ. La orden queda en `PENDING_CONFIRMATION` hasta que vence; el barrido la deja `EXPIRED`, libera sus entradas y pide el reembolso |
| `DOWN` | Falla todos los cobros, como una pasarela caída | Igual que `SLOW`, pero cada intento falla de inmediato con `TKT0007` |

Para cambiar de modo sin reiniciar LocalStack se recrea solo el worker con un override (el bloque
`environment` tiene precedencia sobre `.env`). Con un TTL corto en la API se ve el vencimiento sin
esperar los 10 minutos:

```yaml
services:
  ticketing-api:
    environment:
      ORDERS_RESERVATION_TTL: PT1M
  ticketing-worker:
    environment:
      PAYMENT_SIMULATOR_MODE: DOWN   # o RANDOM, SLOW, DECLINE
```

```bash
docker compose -f docker-compose.yml -f override.yml up -d --no-deps ticketing-api ticketing-worker
# comprar 1 entrada: en el log del worker aparece TKT0007 en cada intento, el mensaje llega a la
# DLQ tras 5 entregas y, al vencer la reserva, el barrido deja la orden EXPIRED
docker compose up -d --no-deps ticketing-api ticketing-worker   # restaurar
```

**Recrear el worker con un mensaje en vuelo.** Si el worker se apaga mientras procesa un mensaje, ese
mensaje queda oculto en SQS durante el tiempo de visibilidad (`SQS_VISIBILITY_TIMEOUT_SECONDS`, 60 s)
antes de que el nuevo contenedor lo reciba. No es un error ni se puede acortar desde el cliente: el
recibo del mensaje lo tenía la conexión del contenedor que se apagó, y el nuevo nunca lo conoce para
devolverlo. Es el comportamiento normal de SQS; la orden se procesa al reaparecer el mensaje.

## Colección de Postman

[`postman/ticketing-platform.postman_collection.json`](postman/ticketing-platform.postman_collection.json)
trae los 6 flujos del RFC con sus aserciones: venta aprobada, pago rechazado, agotado, reintento
idempotente, reserva que vence, y catálogo con disponibilidad y cortesías. Los 6 pasan con Newman
contra `docker compose up`. Se importa en Postman junto
con [`postman/local.postman_environment.json`](postman/local.postman_environment.json), o se corre sin
instalar nada con Newman en contenedor:

```bash
docker run --rm -v "$PWD/postman":/etc/newman postman/newman:6.1.3-alpine \
  run ticketing-platform.postman_collection.json -e local.postman_environment.json \
  --env-var baseUrl=http://host.docker.internal:8080/api/v1 --delay-request 500 \
  --folder "1. Venta aprobada" --folder "3. Agotado (409)" --folder "4. Reintento idempotente" \
  --folder "6. Catálogo, disponibilidad y cortesías"
```

En Linux, cambiar `host.docker.internal` por `localhost` y agregar `--network host`. El flujo 2
necesita el worker con `PAYMENT_SIMULATOR_MODE=DECLINE` y el flujo 5 el override de la sección
anterior; su descripción en la colección lo indica. La carpeta 7 (disponibilidad en vivo por SSE) es
manual: el stream queda abierto y se prueba con el navegador o con `curl -N`.

## Pruebas y calidad

```bash
./gradlew build --max-workers=1
```

Incluye `validateStructure`, el `ArchitectureTest`, las pruebas unitarias y de integración (con
LocalStack en Testcontainers, necesitan Docker), BlockHound para detectar bloqueos en los flujos
reactivos, la verificación de paridad de variables, el gate de cobertura combinada de JaCoCo (90 %
de líneas) y las pruebas de mutación de PIT, que no bloquean el build. Reportes:

- Cobertura combinada: `build/reports/jacoco/jacocoMergedReport/html/index.html`.
- Mutaciones por módulo: `<módulo>/build/reports/pitest/index.html`.

Pruebas destacadas:

| Prueba | Qué demuestra |
| --- | --- |
| `PlaceOrderConcurrencyIntegrationTest` | 200 compras simultáneas contra 50 cupos: exactamente 50 aceptadas y nunca disponibles negativos |
| `IdempotencyIntegrationTest` | Repetir una compra con la misma llave devuelve el mismo resultado sin reservar dos veces |
| `ReservationSweepIntegrationTest` | El barrido libera las reservas vencidas una sola vez, aun con dos réplicas o una carrera con el procesamiento |
| `ComplimentaryIntegrationTest` | Las cortesías mueven exactamente su cantidad y el barrido nunca las toca |
| `PlaceOrderLatencyTest` (`./gradlew performanceTest`) | Mide el p99 de `POST /orders` contra LocalStack. Con una compra a la vez da 102 ms (objetivo 150 ms); con 20 en vuelo sube a ~1 s porque LocalStack comparte los recursos del equipo (ver limitaciones) |

Análisis estático y de dependencias, sin instalar nada en la máquina:

```bash
# SonarQube local del perfil quality (http://localhost:9000) y análisis con la cobertura combinada
(cd deployment && docker compose --profile quality up -d sonarqube)
./gradlew build jacocoMergedReport sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=<token>

# Vulnerabilidades HIGH y CRITICAL de las dependencias
docker run --rm -v "$PWD":/src aquasec/trivy:0.76.1 fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 /src
```

El detalle del análisis está en [CONTRIBUTING.md](CONTRIBUTING.md).

Las dependencias de Gradle están bloqueadas en los `gradle.lockfile` de cada módulo; tras cambiar una
versión se regeneran con `./gradlew resolveAndLockAll --write-locks`.

## Decisiones clave

- **Cero sobreventa por diseño.** El inventario solo cambia con una transacción condicional de
  DynamoDB (`available >= :q`) que, en la misma escritura, verifica que el evento esté a la venta,
  sube el contador del cliente, crea la orden con su auditoría y cierra la llave de idempotencia.
  Nunca se lee, calcula y escribe. El inventario se reparte en porciones para repartir la carga de
  un evento popular.
- **Reserva en la API, pago en el worker.** El cliente recibe en milisegundos una orden que ya tiene
  sus entradas; el cobro, más lento, ocurre fuera del request. Un mensaje se procesa según el estado
  actual de la orden, así que un duplicado o un mensaje tardío no tienen un segundo efecto.
- **Idempotencia en dos fases.** `POST /orders` reclama la `Idempotency-Key` antes de reservar
  (`IN_PROGRESS` con lease) y la cierra con el resultado en la misma transacción de la reserva. Un
  rechazo de negocio queda guardado; un error técnico libera la llave para que el reintento vuelva a
  intentar.
- **Outbox liviano.** Cada orden aceptada queda marcada como pendiente de publicar hasta que SQS
  confirma el envío; el worker republica las pendientes, así que ninguna orden aceptada se pierde.
- **Barrido con lock distribuido.** Cada 30 s una sola réplica del worker toma un lock con lease en
  DynamoDB (escritura condicional, sin dependencias nuevas), consulta solo las órdenes activas
  vencidas por un índice disperso y las libera reutilizando el procesamiento de órdenes. Cada
  liberación es condicional sobre estado y versión, así que una carrera con el procesamiento o con
  otra réplica nunca mueve el inventario dos veces.
- **Vencimiento lógico inmediato.** Una orden se muestra `EXPIRED` en cuanto pasa su vencimiento,
  aunque el barrido la libere físicamente hasta 30 s después.
- **SSE compartido.** Todos los suscriptores de un evento comparten un solo sondeo a DynamoDB y
  reciben solo los cambios.
- **Cortesías como orden final.** Una asignación crea una orden `COMPLIMENTARY` y mueve las entradas
  de disponibles a cortesía en una sola transacción; no cuentan para el límite del cliente.

## Limitaciones conocidas y qué cambiaría en producción

- **Sin autenticación todavía:** la identidad del cliente llega en `X-Customer-Id` y la ruta de
  cortesías no verifica el rol de administrador. La feature 004 agrega JWT, rol de administrador,
  rate limiting y webhook firmado.
- **Cortesías no idempotentes:** repetir la solicitud asigna de nuevo. Se agregará una llave de
  idempotencia junto con el rol de administrador.
- **Latencia bajo concurrencia en local:** con 20 compras simultáneas el p99 de `POST /orders` sube a
  ~1 s, y `./gradlew performanceTest` no pasa en un equipo de desarrollo. No viene del código: una
  lectura simple también pasa de 17 ms a 337 ms con esa concurrencia, porque la app, LocalStack y
  Testcontainers compiten por CPU y memoria en la misma máquina, y LocalStack no reproduce el
  rendimiento de DynamoDB. La validación real es una prueba de carga (k6 o Gatling) contra AWS.
- **Liberación física hasta 30 s más un ciclo** después del vencimiento; la lógica es inmediata. Con
  mensajes con retardo de SQS se podría liberar al segundo.
- **Simulador de pagos** en lugar de una pasarela real, con modos lento, aleatorio y caído para
  demostrar los reintentos, la DLQ y el vencimiento con reembolso.
- **En producción** agregaría una sala de espera virtual para los lanzamientos masivos, el outbox
  completo con DynamoDB Streams, asientos numerados, reportes contables desde la auditoría exportada
  a S3 y SLOs con pruebas de caos. El RFC (sección 10.4) lo detalla.

## Hoja de ruta

| Feature | Alcance | Estado |
| --- | --- | --- |
| 001 | Eventos, inventario por porciones y disponibilidad (individual, por lote y SSE) | Entregada |
| 002 | Compra con reserva atómica, idempotencia, cola con DLQ, worker, simulador de pagos y consulta de la orden | Entregada |
| 003 | Barrido de reservas vencidas con lock distribuido y entradas de cortesía | Entregada |
| 003b | Modos de demostración del simulador (lento, aleatorio, caído) | Entregada |
| 004 | Seguridad y resiliencia: JWT, rol de administrador, rate limiting, webhook firmado, circuit breaker | Siguiente |
| 005 | Infraestructura en AWS con Terraform | Siguiente |

## Documentación

- Diseño funcional y técnico: [docs/rfc/RFC-001-ticketing.md](docs/rfc/RFC-001-ticketing.md).
- Primeros pasos: [GETTINGSTARTED.md](GETTINGSTARTED.md).
- Cambios por versión: [CHANGELOG.md](CHANGELOG.md).
- Cómo contribuir: [CONTRIBUTING.md](CONTRIBUTING.md) y [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
- Licencia: [LICENSE.md](LICENSE.md).
