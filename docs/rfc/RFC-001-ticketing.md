# RFC-001 · Plataforma de Procesamiento de Eventos de Ticketing

Octubre 2026 · Milton Sánchez

## 1. Resumen

**Resuelve:** RNF-15 (objetivos de rendimiento)

Propongo un backend reactivo (Java 25, Spring Boot 4, WebFlux) organizado en Clean Architecture con el scaffold de Bancolombia. La reserva de entradas es una escritura condicional atómica en DynamoDB, ejecutada en la API en milisegundos. La confirmación de la compra se delega a un consumidor de SQS con entrega at-least-once. La sobreventa queda descartada por diseño: el inventario solo se descuenta con `available >= :qty` dentro de una transacción, nunca con un patrón leer-y-escribir.

**Contexto.** El sistema actual vende el mismo asiento a varias personas, duplica compras y agota tiempos de espera cuando se liberan eventos populares. La causa raíz típica es el patrón leer-validar-escribir sin control de concurrencia, sumado a hilos bloqueados esperando a la base de datos.

**Objetivos**

- Cero sobreventa bajo cualquier nivel de concurrencia (invariante de dominio, no "mejor esfuerzo").
- Respuesta de la API de compra en milisegundos de un dígito o dos (p99), con el trabajo pesado fuera del request.
- Reserva temporal de 10 minutos con liberación automática.
- Idempotencia de extremo a extremo: un reintento del cliente o un mensaje duplicado de SQS no generan una segunda orden ni un segundo descuento.
- Transiciones de estado atómicas y auditables.
- Ejecución local completa con `docker compose up` y camino claro a producción en AWS con Terraform.

**¿Qué es el p99?** Es el tiempo de respuesta por debajo del cual queda el 99 % de las peticiones; solo el 1 % más lento lo supera. Si ordenamos 100 peticiones de la más rápida a la más lenta, el p99 es el tiempo de la número 99.

| Percentil | Significa | Para qué se mira |
| --- | --- | --- |
| p50 (mediana) | La mitad responde en este tiempo o menos | La experiencia típica |
| p95 | 95 de cada 100 responden en este tiempo o menos | La experiencia de casi todos |
| p99 | 99 de cada 100 responden en este tiempo o menos | Los casos lentos, que son los que generan timeouts y reintentos |

Se usa el p99 y no el promedio porque el promedio esconde los casos lentos. En un lanzamiento con 60.000 compras por minuto, ese 1 % son 600 personas por minuto esperando, justo las que reintentan y agravan el pico. El objetivo de este diseño es que `POST /orders` tenga un p99 menor a 100 ms. Se mide con histogramas de Micrometer en producción (sección 9) y con la prueba de carga (sección 10.2).

**No-objetivos (fuera del alcance de la prueba)**

- Integración real con una pasarela de pagos: se modela como puerto con un adaptador simulado.
- Selección de asiento numerado en un mapa del recinto: el modelo es por cantidad, con  la extensión a asientos descrita en la sección 10.
- Frontend, notificaciones al cliente y sala de espera virtual (se proponen como mejoras).

## 2. Requisitos y supuestos

Cada requisito de la prueba tiene un mecanismo concreto en el diseño; esta tabla sirve de guía para la sesión de evaluación.

**Requisitos funcionales**

| Req. | Requisito | Mecanismo en el diseño | Sección |
| --- | --- | --- | --- |
| RF1 | Gestión de eventos | `POST/GET /api/v1/events`; el agregado `Event` crea su `EventInventory` en la misma transacción | 3, 7 |
| RF2 | Reserva temporal (máx. 10 min) | Transacción DynamoDB: descuento condicional + orden `RESERVED` con `expiresAt = now + 10 min`. Vencimiento lógico exacto: ninguna confirmación se acepta después de `expiresAt` | 5, 6 |
| RF3 | Procesamiento asíncrono | 202 Accepted con `orderId`; mensaje `OrderPlaced` en SQS; el consumidor valida la reserva, mueve el inventario de reservado a vendido y cambia el estado (supuesto 2) | 5, 7 |
| RF4 | Consulta de estado de orden | `GET /api/v1/orders/{orderId}` con lectura fuertemente consistente; estado de la orden y estado de cada entrada (supuesto 1) | 7 |
| RF5 | Control de concurrencia | Escrituras condicionales + versión (optimistic locking) + guardas de máquina de estados | 6 |
| RF6 | Liberación de reservas expiradas | Barrido periódico sobre un GSI disperso por `expiresAt`, con lock distribuido; TTL configurable para demostrarlo | 5, 6, 10 |
| RF7 | Disponibilidad en tiempo real | `GET /events/{id}/availability`, consulta por lote `GET /events/availability?ids=...` (Flux) y `/availability/stream` (SSE) | 7 |
| RF-E | Reglas de estados (notas generales) | Un estado por entrada; transiciones con condición + auditoría en la misma transacción; `SOLD` y `COMPLIMENTARY` finales | 3 |

**Requisitos no funcionales**

Agrupan el stack obligatorio, las especificaciones técnicas, los entregables y los criterios de evaluación de la prueba. Cada sección del RFC empieza con una línea **Resuelve:** que indica qué requisitos atiende.

| Id | Requisito | Solución en el diseño | Sección |
| --- | --- | --- | --- |
| RNF-01 | Java 25 (records, pattern matching, virtual threads si aplica) | Value objects y comandos como `record`; resultados de dominio `sealed` con `switch` de patrones; virtual threads descartados a propósito porque no hay código bloqueante | 3, 4 |
| RNF-02 | Spring Boot 4.x | Proyecto generado con el scaffold y llevado a Spring Boot 4.x | 4 |
| RNF-03 | Spring WebFlux; API completamente reactiva con `Mono` y `Flux` | Router y handlers WebFlux; AWS SDK v2 asíncrono; ningún bloqueo en el event loop | 4, 7 |
| RNF-04 | Persistencia de eventos, órdenes e inventario | DynamoDB (LocalStack en desarrollo), una tabla por bounded context | 6, 10 |
| RNF-05 | Cola de mensajes para procesar órdenes | SQS en LocalStack: `order-placed` y `payment-confirmed`, cada una con DLQ | 7, 10 |
| RNF-06 | Docker y `docker-compose.yml` con todos los servicios | Dockerfile multi-stage y compose con LocalStack (DynamoDB, SQS y Secrets Manager), API y worker | 4, 10 |
| RNF-07 | Clean Architecture con capas Domain, Use Cases e Infrastructure separadas | Scaffold de Bancolombia; `validateStructure` verifica la dirección de las dependencias en cada build | 4 |
| RNF-08 | Manejo de errores reactivo con reintentos | `Retry.backoff` con jitter solo para errores transitorios; manejador global de errores con un formato de error estándar (\`errors\[\]\` con código) | 7 |
| RNF-09 | SQS con entrega at-least-once | Borrado del mensaje solo al completar el `Mono`; visibility timeout; DLQ tras 5 intentos; consumidor idempotente | 7 |
| RNF-10 | Optimistic locking o conditional writes en el inventario | Escritura condicional `available >= :q`; versión en `Order` y `Event`; transacciones | 6 |
| RNF-11 | Código en inglés, principios SOLID y patrones de diseño | Convenciones de código y catálogo de patrones aplicados | 4 |
| RNF-12 | README con descripción, instalación, Docker, decisiones y ejemplos | Contenido del README definido a partir de este RFC | 10 |
| RNF-13 | Pruebas con cobertura ≥ 90 % (casos de uso, WebFlux, concurrencia; JUnit 5, Mockito, reactor-test) | Estrategia de pruebas con gate de JaCoCo y prueba de concurrencia de 200 compras contra 50 cupos | 10 |
| RNF-14 | Colección de solicitudes con los flujos principales | Colección Postman con 6 flujos, incluido "Reserva que vence" | 10 |
| RNF-15 | Rendimiento: respuesta baja bajo miles de solicitudes concurrentes | Reserva en un solo viaje a DynamoDB; trabajo pesado en el worker; stream SSE compartido | 1, 5, 6, 7 |
| RNF-16 | Escalabilidad | API y worker escalan por separado; inventario en shards; DynamoDB on-demand; autoscaling por peticiones y por backlog | 4, 6, 9 |
| RNF-17 | Resiliencia y tolerancia a fallos | Reintentos, circuit breaker, DLQ, outbox liviano, compensación por reembolso, despliegue en 3 AZ | 5, 7, 9 |
| RNF-18 | Seguridad: secretos, reintentos maliciosos, idempotencia, abuso de recursos | `Idempotency-Key`, límites en la transacción, WAF y rate limit, webhook HMAC, IAM Task Roles, Secrets Manager, KMS | 8 |
| RNF-19 | Explicación clara: diagramas de arquitectura, secuencia e interacciones | Modelo de dominio, estados, componentes, microservicios y colas, 3 secuencias, idempotencia, AWS | 3, 4, 5, 8, 9 |
| RNF-20 | Infraestructura como código con Terraform (red, seguridad, escalabilidad, ambientes) | Módulos por dominio, un ambiente por cuenta AWS, estado remoto cifrado, validaciones en el pipeline | 9 |
| RNF-21 | Experiencia cloud-native en AWS: seguridad, operación, costos, observabilidad, gobierno | Arquitectura objetivo, decisiones de plataforma, métricas y alarmas, palancas de costo, etiquetas y servicios de gobierno | 9 |
| RNF-22 | Madurez: limitaciones, mejoras y cambios para producción | Alternativas consideradas, limitaciones y qué cambiaría en producción | 10 |

**Supuestos (a validar con el evaluador)**

1. **Estado de la orden vs. estado de la entrada.** El enunciado lista para la orden los 5 estados de la entrada. La respuesta de `GET /orders/{id}` expone ambos niveles. El `status` de la orden es su ciclo de vida: `RESERVED`, `PENDING_CONFIRMATION`, `SOLD`, `COMPLIMENTARY`, y los terminales `EXPIRED` y `CANCELLED`. `tickets[].status` usa exactamente el vocabulario del enunciado: en una orden vencida o cancelada, sus entradas aparecen como `AVAILABLE` porque volvieron al inventario. Así se cumple literalmente sin perder el motivo.
2. **Reserva en la API, confirmación en el consumidor.** El enunciado sugiere que el consumidor valida la disponibilidad y actualiza el inventario. En este diseño la disponibilidad se valida y se aparta en la API, con una escritura condicional atómica de milisegundos, y la orden se encola después. El consumidor revalida que la reserva siga vigente y que el evento siga activo, mueve el inventario de `reserved` a `sold` y cambia el estado. Motivo: el usuario sabe al instante si consiguió entradas, y ninguna orden aceptada termina luego en agotado (sección 5.1).
3. **Inventario por cantidad (admisión general).** Una entrada no existe como registro hasta que se reserva; `AVAILABLE` es la capacidad no asignada. La entrada nace `RESERVED` dentro de su orden.
4. **`PENDING_CONFIRMATION` = esperando confirmación de pago.** El consumidor solicita el pago a un puerto `PaymentGateway`. En local, un simulador con modos configurables. Con una pasarela real, la confirmación llega por webhook firmado. "Confirmar la compra" en RF2 equivale a pago aprobado.
5. **`COMPLIMENTARY`** se emite por un endpoint administrativo. Descuenta inventario pero no cuenta como venta.
6. **Límites anti-abuso** configurables: máximo 6 entradas por orden y por cliente por evento (valor por defecto a confirmar con negocio).
7. **Autenticación** JWT (OAuth2 Resource Server). En el entorno local (application-local.yaml) se acepta un JWT de prueba firmado con una llave local. Mientras llega esta capa (feature 004), la primera entrega identifica al cliente con la cabecera X-Customer-Id (UUID); el JWT la reemplaza por su sub sin cambiar los casos de uso.

## 3. Modelo de dominio (DDD)

**Resuelve:** RF1, RF4, RF-E · RNF-01, RNF-11, RNF-19

El dominio se divide en tres bounded contexts con un agregado raíz cada uno: `Event`, `EventInventory` y `Order`. Separar el inventario del catálogo es la decisión clave. El inventario es el único punto de contención y debe ser un registro pequeño, actualizable de forma atómica sin tocar los datos descriptivos del evento.

![Modelo de dominio](diagrams/domain-model.png)

| Bounded context | Agregado raíz | Responsabilidad | Invariantes que protege |
| --- | --- | --- | --- |
| Event Catalog | `Event` | Datos descriptivos: nombre, fecha, lugar, capacidad | Capacidad > 0, fecha futura al crear |
| Inventory | `EventInventory` | Contadores por estado de entrada | `available + reserved + sold + complimentary = capacity`; `available >= 0` |
| Ordering | `Order` (contiene `Ticket`) | Ciclo de vida de la compra y sus entradas | Transiciones válidas; `SOLD` y `COMPLIMENTARY` son finales; máx. 6 entradas |

### 3.1 Diccionario de datos

Cada campo del modelo con su nombre en el código (inglés), su tipo y para qué sirve. Los identificadores son UUID generados por el servidor, salvo la llave de idempotencia, que genera el cliente.

**`Event`: el evento (Event Catalog)**

| Campo | Tipo | Para qué sirve |
| --- | --- | --- |
| `eventId` | `EventId` (UUID) | Identificador único del evento |
| `name` | `String` | Nombre del evento, por ejemplo "Concierto Shakira Bogotá" |
| `venue` | `Venue` | Lugar donde se realiza el evento |
| `date` | `Instant` | Fecha y hora de inicio, en UTC |
| `capacity` | `Capacity` (entero > 0) | Total de entradas del evento; no cambia después de crearlo |
| `status` | `EventStatus` | Si el evento admite compras: `ACTIVE` (a la venta), `CANCELLED` (cancelado) o `FINISHED` (ya ocurrió) |
| `version` | `long` | Número de versión para optimistic locking: evita que dos cambios simultáneos se pisen |

**`Venue`: el lugar (value object)**

| Campo | Tipo | Para qué sirve |
| --- | --- | --- |
| `name` | `String` | Nombre del recinto, por ejemplo "Movistar Arena" |
| `city` | `String` | Ciudad del recinto |

**`EventInventory`: el inventario del evento (Inventory)**

| Campo | Tipo | Para qué sirve |
| --- | --- | --- |
| `eventId` | `EventId` | Evento al que pertenece el inventario (uno por evento) |
| `capacity` | `int` | Copia de la capacidad total, para validar la invariante sin consultar el evento |
| `available` | `int` | Entradas que se pueden comprar ahora mismo |
| `reserved` | `int` | Entradas apartadas en órdenes `RESERVED` o `PENDING_CONFIRMATION`; aún no son venta |
| `sold` | `int` | Entradas vendidas; cuenta para reportes contables |
| `complimentary` | `int` | Entradas de cortesía emitidas; no cuentan como venta |
| `version` | `long` | Versión del registro; sube en cada cambio y sirve para auditoría y lecturas |

La suma `available + reserved + sold + complimentary` siempre es igual a `capacity`.

**`InventoryShard`: porción del inventario.** El inventario de un evento se guarda en N porciones (`shardId` de 0 a N − 1) con los mismos contadores, y cada una cumple la misma invariante. `EventInventory` es la suma de sus porciones.

**`Order`: la orden de compra (Ordering)**

| Campo | Tipo | Para qué sirve |
| --- | --- | --- |
| `orderId` | `OrderId` (UUID) | Identificador único de la orden; es el que recibe el cliente al comprar |
| `eventId` | `EventId` | Evento para el que se compran las entradas |
| `customerId` | `CustomerId` | Cliente dueño de la orden; sale del token JWT, nunca del body |
| `status` | `OrderStatus` | Estado actual de la orden en su ciclo de vida (ver máquina de estados) |
| `quantity` | `Quantity` (1 a 6) | Cuántas entradas incluye la orden |
| `tickets` | `List<Ticket>` | Las entradas individuales de la orden |
| `expiresAt` | `Instant` | Momento en que vence la reserva (creación + 10 min); después ya no se puede confirmar |
| `idempotencyKey` | `IdempotencyKey` (UUID) | Llave enviada por el cliente para que un reintento no cree otra orden |
| `paymentReference` | `String` | Referencia del pago en la pasarela; se llena cuando la orden se vende |
| `createdAt` | `Instant` | Momento en que se creó la orden |
| `version` | `long` | Versión para optimistic locking entre el worker y el barrido |

**`Ticket`: la entrada (entidad dentro de `Order`)**

| Campo | Tipo | Para qué sirve |
| --- | --- | --- |
| `ticketId` | `TicketId` (UUID) | Identificador de la entrada; es lo que se validaría en la puerta del evento |
| `status` | `TicketStatus` | Estado de la entrada con el vocabulario del enunciado: `AVAILABLE`, `RESERVED`, `PENDING_CONFIRMATION`, `SOLD`, `COMPLIMENTARY` |

**`OrderStatusChange`: el registro de auditoría (domain event)**

| Campo | Tipo | Para qué sirve |
| --- | --- | --- |
| `orderId` | `OrderId` | Orden que cambió de estado |
| `from` | `OrderStatus` | Estado anterior (vacío cuando la orden se crea) |
| `to` | `OrderStatus` | Estado nuevo |
| `at` | `Instant` | Momento exacto del cambio |
| `reason` | `String` | Motivo: `PLACED`, `PAYMENT_APPROVED`, `PAYMENT_DECLINED`, `EXPIRED`, `COMPLIMENTARY_GRANTED` |

**Campos técnicos de persistencia** (existen en DynamoDB, no en el modelo de dominio)

| Campo | Tabla | Para qué sirve |
| --- | --- | --- |
| `activeBucket` | `orders` | Marca la orden como activa para el índice del barrido; se borra al llegar a un estado final |
| `publishedAt` | `orders` | Momento en que la orden se publicó en SQS; si está vacío, el barrido la vuelve a publicar |
| `requestHash` | `idempotency` | Huella SHA-256 del body, para detectar la misma llave con otro contenido |
| `ttl` | `idempotency`, `customer_event_limits` | Fecha en que DynamoDB borra el registro automáticamente |
| `ticketsHeld` | `customer_event_limits` | Entradas que un cliente tiene en un evento, para aplicar el límite de 6 |

**Lenguaje ubicuo.** *Reserva*: entradas apartadas para una orden con vencimiento. *Confirmación*: paso de reserva a venta tras el pago. *Liberación*: devolución de una reserva vencida o cancelada al inventario. *Cortesía*: entrada emitida sin venta, final y no contable.

**Reglas de modelado**

- Agregados referenciados por id, nunca por objeto. `Order` guarda `eventId`, no un `Event`.
- Una transacción de negocio modifica `Order` y `EventInventory` juntos. Es la única excepción consciente a "un agregado por transacción". Se justifica porque la consistencia entre ambos es la invariante central (no sobreventa) y DynamoDB la ofrece con `TransactWriteItems`.
- Value objects como `record` de Java 25 con validación en el constructor compacto: `Quantity`, `EventId`, `OrderId`, `IdempotencyKey`, `Capacity`.
- Resultados de dominio como jerarquías `sealed` evaluadas con `switch` de pattern matching: `PaymentResult = Approved | Declined | Pending`, `ReservationOutcome = Reserved | SoldOut | LimitExceeded`. El compilador obliga a manejar cada caso.
- La máquina de estados vive en el dominio (`OrderStatus.canTransitionTo`). La persistencia la refuerza con condiciones, pero no la define.

**Máquina de estados**

![Máquina de estados de la orden](diagrams/state-machine.png)

Las entradas de una orden comparten siempre su estado, porque se compran y se liberan juntas. Cada transición escribe un `OrderStatusChange` en la tabla de auditoría dentro de la misma transacción. Así no puede existir un cambio de estado sin su registro, ni un registro sin su cambio.

## 4. Arquitectura

**Resuelve:** RNF-01, RNF-02, RNF-03, RNF-06, RNF-07, RNF-11, RNF-16, RNF-19

Es un solo código base en Clean Architecture, empaquetado en una sola imagen y desplegado como dos servicios: `ticketing-api` (HTTP) y `ticketing-worker` (SQS y barrido). Cada servicio escala por su propia señal: la API por peticiones, el worker por mensajes pendientes. Así no hace falta operar dos repositorios para lo que hoy es un solo dominio.

![Diagrama de componentes](diagrams/components.png)

**Relación entre los dos microservicios**

![microservicios y colas · flujo de compra en 9 pasos](diagrams/microservices-queues.png)

La API solo publica en las colas y el worker solo las consume; no existe ninguna llamada directa entre ellos. Ambos usan la misma base de datos, y la consistencia la garantizan las escrituras condicionales.

1. El cliente inicia la compra (`POST /orders`).
2. La API reserva las entradas en DynamoDB de forma atómica (orden `RESERVED`) y responde 202 con el `orderId`.
3. La API publica `OrderPlaced` en la cola `order-placed`.
4. El worker consume la orden.
5. El worker solicita el cobro a la pasarela. Si la pasarela responde en el acto, salta al paso 9.
6. Si el cobro es asíncrono, la pasarela avisa por webhook a la API.
7. La API valida la firma y publica `PaymentConfirmed` en la cola `payment-confirmed`.
8. El worker consume la confirmación.
9. El worker deja la orden en `SOLD` o `CANCELLED`. Por su lado, el barrido marca `EXPIRED` las reservas vencidas y devuelve las entradas.

El cliente consulta el estado con `GET /orders/{id}` en cualquier momento; la API lo lee de DynamoDB.

**Estilo arquitectónico**

- **Hexagonal / Clean Architecture** del scaffold de Bancolombia. `domain/model` define puertos (gateways), `domain/usecase` orquesta y `infrastructure` implementa adaptadores. Las dependencias apuntan siempre hacia el dominio; la tarea `validateStructure` del plugin lo verifica en cada compilación.
- **Reactivo de punta a punta.** Netty y WebFlux en la entrada; AWS SDK v2 asíncrono (`DynamoDbEnhancedAsyncClient`, `SqsAsyncClient`) en la salida. Ninguna llamada bloquea el event loop. Los virtual threads de Java 25 no aportan aquí porque no hay código bloqueante que envolver; se descartan a propósito para no mezclar modelos.
- **Event-driven con colas.** SQS desacopla la aceptación de la compra de su confirmación y absorbe los picos: si el worker va lento, crece la cola, no la latencia de la API.

**Generación con el scaffold** (plugin `co.com.bancolombia.cleanArchitecture` 5.0.4, wrapper de Gradle 9.8.1, JDK Temurin 25)

```bash
gradle ca --package=co.com.nequi.ticketing --type=reactive --name=TicketingPlatform --java-version=25 --lombok=true --metrics=true --mutation=true
gradle gm --name=Event && gradle gm --name=Inventory && gradle gm --name=Order
gradle guc --name=PlaceOrder        # y el resto de casos de uso
gradle gep --type=webflux --router=true
gradle gep --type=sqs               # sqs-listener
gradle gep --type=generic --name=ReservationSweeper
gradle gda --type=dynamodb
gradle gda --type=sqs               # sqs-sender
gradle gda --type=generic --name=PaymentSimulator
gradle gda --type=secrets
```

Una prueba previa del scaffold confirmó que el plugin ya genera el proyecto en Java 25 y Spring Boot 4.1.x, con `validateStructure` y `ArchitectureTest` en verde. Spring Boot se fija en la última 4.1.x estable y se compila solo con un JDK 25 GA. Algunos archivos generados se ajustan en la fase de setup para cumplir las reglas del proyecto: `.gitignore`, `application.yaml` sin valores fijos, Dockerfile multi-stage con imagen base por argumento y logs en JSON. Lombok se usa en infraestructura y casos de uso (`@RequiredArgsConstructor`, `@Slf4j`, `@Builder`); el dominio usa `record` para value objects y eventos.

**Estructura de módulos**

```text
ticketing-platform/
├── applications/app-service          # MainApplication, UseCasesConfig, rol APP_ROLE=api|worker
├── domain/
│   ├── model/                        # event/, inventory/, order/, shared/ (VOs, errores sealed)
│   │   └── */gateways/               # puertos: repositorios, publisher, payment, idempotency
│   └── usecase/                      # un paquete por caso de uso
├── infrastructure/
│   ├── entry-points/
│   │   ├── reactive-web/             # RouterRest + Handlers + DTOs + GlobalErrorWebExceptionHandler
│   │   ├── sqs-listener/
│   │   └── reservation-sweeper/
│   ├── driven-adapters/
│   │   ├── dynamo-db/
│   │   ├── sqs-sender/
│   │   ├── payment-simulator/
│   │   └── secrets/
│   └── helpers/                      # commons (excepciones técnicas), logging, json-commons, message-contracts
├── deployment/
│   ├── Dockerfile                    # multi-stage, JRE 25 distroless, usuario no root
│   ├── docker-compose.yml
│   ├── localstack/init/ready.d/      # creación de colas, DLQs y secretos
│   └── terraform/                    # modules/ + envs/{dev,qa,prod}
└── docs/                             # este RFC, diagramas .puml, colección Postman
```

**Casos de uso**

| Caso de uso | Disparador | Puertos que usa |
| --- | --- | --- |
| `CreateEventUseCase` | `POST /events` | `EventRepository` (crea `Event` + `EventInventory` en una transacción) |
| `GetEventUseCase`, `ListEventsUseCase` | `GET /events` | `EventRepository` |
| `GetAvailabilityUseCase` | `GET /events/{id}/availability`, SSE | `InventoryRepository` |
| `PlaceOrderUseCase` | `POST /orders` | `IdempotencyStore`, `ReservationUnitOfWork`, `OrderEventPublisher` |
| `ProcessOrderUseCase` | SQS `order-placed` | `OrderRepository`, `PaymentGateway`, `ReservationUnitOfWork` |
| `ConfirmPaymentUseCase` | SQS `payment-confirmed` (desde el webhook) | `OrderRepository`, `ReservationUnitOfWork` |
| `GetOrderUseCase` | `GET /orders/{id}` | `OrderRepository` |
| `ReleaseExpiredReservationsUseCase` | Barrido periódico | `OrderRepository`, `ReservationUnitOfWork`, `OrderEventPublisher` |
| `GrantComplimentaryUseCase` | `POST /events/{id}/complimentary` (rol admin) | `ReservationUnitOfWork` |

**Convenciones de código y patrones (RNF-11)**

Todo el código está en inglés: paquetes, clases, métodos, variables, comentarios, mensajes de log y nombres de campos de la API. La documentación (este RFC y el README) va en español. Convenciones: MapStruct para mapear DTOs, OpenAPI con springdoc por cada RouterRest y Bean Validation (Jakarta) para validar la entrada. Además se aplica un catálogo de reglas de codificación. Entre otras: configuración sin valores por defecto y solo con los archivos application.yaml y application-local.yaml, perfiles solo local y no local (el rol API o worker es la variable APP\_ROLE, no un perfil), PRs de máximo 200 líneas y casos de uso que nunca son pass-through.

| Principio SOLID | Cómo se aplica |
| --- | --- |
| Responsabilidad única | Un caso de uso por clase; handlers que solo traducen HTTP a comandos; adaptadores que solo traducen al SDK de AWS |
| Abierto/cerrado | Nuevos medios de pago o almacenes se agregan como adaptadores nuevos, sin tocar los casos de uso |
| Sustitución de Liskov | Simulador y pasarela real cumplen el mismo contrato `PaymentGateway`; las pruebas usan dobles intercambiables |
| Segregación de interfaces | Puertos pequeños y específicos (`OrderRepository`, `IdempotencyStore`, `OrderEventPublisher`) en lugar de un repositorio genérico |
| Inversión de dependencias | El dominio define los puertos; la infraestructura los implementa; `app-service` los conecta |

| Patrón | Dónde |
| --- | --- |
| Hexagonal / Clean Architecture (puertos y adaptadores) | Estructura completa del proyecto |
| Aggregate, Value Object, Domain Event (DDD) | `Event`, `EventInventory`, `Order`; records; `OrderStatusChange` |
| Repository y Unit of Work | `OrderRepository`; `ReservationUnitOfWork` sobre `TransactWriteItems` |
| State machine | `OrderStatus` y sus transiciones válidas |
| Strategy | Implementaciones de `PaymentGateway` (simulador, pasarela real) |
| Transactional outbox (liviano) | Re-publicado de órdenes no publicadas desde el barrido |
| Idempotent receiver / consumer | Listener que decide por el estado de la orden |
| Retry con backoff, circuit breaker, dead letter queue | Adaptadores AWS, pasarela de pagos, colas |
| Compensating transaction | Reembolso cuando un pago llega después del vencimiento |

## 5. Flujos principales

**Resuelve:** RF2, RF3, RF6 · RNF-15, RNF-17, RNF-19

Tres flujos cubren los requisitos de compra: iniciar compra (síncrono, de milisegundos), procesar y confirmar (asíncrono) y liberar reservas vencidas (periódico). Los tres se coordinan solo a través de escrituras condicionales sobre el mismo registro de orden; no hay locks en memoria.

### 5.1 Iniciar compra

![Secuencia: iniciar compra](diagrams/seq-place-order.png)

La API hace en un solo viaje a DynamoDB lo que antes eran cinco pasos sin aislamiento. Si cualquier condición falla, no se escribe nada. Por eso responde 409 de inmediato, sin encolar trabajo que de antemano sabe inútil. El encolado ocurre después de confirmar la transacción. Si el envío a SQS falla, la compra igual responde 202: la orden nace marcada como pendiente de publicar en un GSI disperso (outbox liviano), y el worker la republica en el siguiente ciclo. Así ninguna orden aceptada queda sin procesar.

Decisión: reservar en la API y no en el consumidor. El usuario sabe en el acto si consiguió entradas, y el consumidor no tiene que rechazar órdenes ya aceptadas. La alternativa "encolar sin reservar" devuelve un `orderId` que luego puede terminar en `SOLD_OUT`, lo que es peor experiencia y genera más carga de consulta de estado (*polling*).

### 5.2 Procesamiento asíncrono y confirmación

![Secuencia: procesamiento asíncrono](diagrams/seq-process-order.png)

El consumidor es idempotente por construcción: decide qué hacer según el estado actual de la orden, no según el mensaje. Un duplicado sobre una orden final no hace nada. Una reentrega sobre `PENDING_CONFIRMATION` (por caída a mitad del proceso) retoma consultando el pago con `orderId` como llave de idempotencia ante la pasarela. Así nunca se cobra dos veces.

### 5.3 Liberación de reservas expiradas

![Secuencia: liberación de reservas](diagrams/seq-expiration.png)

El límite de 10 minutos se cumple exacto porque el vencimiento tiene dos partes:

| Vencimiento | Cuándo ocurre | Qué garantiza |
| --- | --- | --- |
| **Lógico** | Exactamente en `expiresAt` (minuto 10) | Ninguna venta se confirma después: la transacción de venta exige `expiresAt > :now`. `GET /orders/{id}` ya muestra `EXPIRED` aunque el barrido no haya pasado |
| **Físico** | En el siguiente barrido, a lo sumo 30 s después | Las entradas vuelven a los contadores de inventario y la orden sale del índice de activas |

El barrido corre cada 30 s y consulta solo órdenes activas gracias a un GSI disperso (sección 6). Un lock distribuido evita que todas las réplicas hagan el mismo trabajo; aunque dos barrieran a la vez, la condición de la transacción hace la liberación idempotente. El lock es una escritura condicional con lease en una tabla de DynamoDB (scheduler\_locks): reactivo y sin dependencias nuevas. Se prefirió a ShedLock porque su proveedor de DynamoDB usa el cliente síncrono. Además, el worker libera en el acto cualquier orden vencida que procese, con la misma transacción condicional; el barrido se encarga de las que ningún mensaje toca. Si negocio necesita que también la liberación física sea al segundo, se suma la alternativa de mensajes con retardo de la sección 10.

**Carrera pago vs. vencimiento.** Las condiciones de ambas transacciones son mutuamente excluyentes: la venta exige `expiresAt > now` y el vencimiento `expiresAt <= now`. Antes del minuto 10 solo puede ganar la venta; después, solo el vencimiento. Si la pasarela aprueba un pago tarde, el consumidor recibe `ConditionalCheckFailed` y dispara el reembolso (compensación). Ambos casos quedan auditados.

## 6. Concurrencia, consistencia y modelo de datos

**Resuelve:** RF2, RF5, RF6, RF7 · RNF-04, RNF-10, RNF-15, RNF-16

La sobreventa se evita con una sola regla: el inventario nunca se escribe con un valor calculado en la aplicación. Se escribe con una expresión atómica que DynamoDB evalúa en el servidor bajo su propia serialización por ítem.

```text
UpdateExpression:    SET available = available - :q, reserved = reserved + :q, version = version + :one
ConditionExpression: available >= :q
```

Dos peticiones simultáneas por las últimas 2 entradas no pueden ganar ambas: la segunda ve `available = 0` en el servidor y falla. No hay ventana entre leer y escribir porque no hay lectura.

**Cuatro mecanismos, cada uno con su propósito**

| Mecanismo | Dónde | Qué evita |
| --- | --- | --- |
| Escritura condicional sobre contadores | `EventInventory` | Sobreventa. No requiere reintento por conflicto, solo falla si realmente no hay cupo |
| Optimistic locking (`version`) | `Order`, `Event` | Actualizaciones perdidas cuando dos procesos modifican la misma orden con datos leídos |
| Guardas de máquina de estados (`status = :expected`) | Transiciones de `Order` | Doble procesamiento por mensajes duplicados y carreras consumidor vs. barrido |
| `TransactWriteItems` | Orden + inventario + idempotencia + auditoría | Estados intermedios: o se aplica todo o nada |

En el inventario se usa condición sobre el valor y no sobre la versión a propósito. Con versión, 1.000 compradores simultáneos con cupo de sobra fallarían casi todos por conflicto y reintentarían en cascada. Con la condición de valor, todos los que caben ganan a la primera. La versión se incrementa igual, para detectar cambios en lecturas y para auditoría.

**Tablas** (una tabla por bounded context, para poder separar servicios más adelante sin migrar datos)

| Tabla | PK | SK | Atributos clave | Índices / TTL |
| --- | --- | --- | --- | --- |
| `events` | `eventId` | — | name, venue, date, capacity, status, version | GSI `by-date` (status, date) para listar |
| `inventory` | `eventId` | `shardId` | capacity, available, reserved, sold, complimentary, version | — |
| `orders` | `orderId` | — | eventId, customerId, status, quantity, tickets\[\], expiresAt, publishedAt, version | GSI disperso `expiring-reservations`: PK `activeBucket`, SK `expiresAt` |
| `order_audit` | `orderId` | `at#seq` | from, to, reason, actor, traceId | Exportable a S3 para reportes contables |
| `idempotency` | `customerId#key` | — | orderId, requestHash, response | TTL 24 h |
| `customer_event_limits` | `customerId#eventId` | — | ticketsHeld | TTL a la fecha del evento |

**GSI disperso para el barrido.** El atributo `activeBucket` (por ejemplo `ACTIVE#0` a `ACTIVE#9`) solo existe mientras la orden está en `RESERVED` o `PENDING_CONFIRMATION`. Al pasar a un estado final, la misma transacción lo elimina y la orden sale del índice. El barrido consulta órdenes activas vencidas sin escanear la tabla. Los 10 buckets reparten las escrituras del índice y evitan una partición caliente.

**Lecturas.** El estado de la orden y la validación del consumidor usan lectura fuertemente consistente. El inventario también se lee con consistencia fuerte en la consulta, el lote y el stream: una lectura eventual puede devolver un estado anterior con más entradas disponibles, y la disponibilidad nunca debe sobrestimarse. Cuesta el doble de unidades de lectura, pero el stream comparte un solo sondeo por evento y por instancia.

**Partición caliente en eventos masivos.** Un solo ítem de DynamoDB admite hasta unas 1.000 escrituras por segundo. Para un lanzamiento con más demanda, el inventario se divide en N shards (`shardId`), cada uno con una porción de la capacidad. La reserva elige un shard al azar y, si está agotado, prueba otro. La disponibilidad es la suma de los shards. Las porciones son parte del dominio (InventoryShard) y N sale de la demanda simultánea esperada, no de la capacidad: N = ceil(escrituras pico por segundo / 500), entre 1 y 20, con 1 por defecto. El tope de 20 mantiene la creación del evento dentro de una sola transacción.

## 7. Mensajería, resiliencia y API

**Resuelve:** RF1, RF3, RF4, RF7 · RNF-03, RNF-05, RNF-08, RNF-09, RNF-15, RNF-17

La garantía at-least-once la da SQS; la exactamente-una-vez de negocio la da el consumidor idempotente. Un mensaje se borra solo cuando el `Mono` de su procesamiento completa sin error. Nunca se procesa con `subscribe()` suelto y borrado inmediato, porque eso pierde mensajes ante una caída.

**Configuración de colas**

| Parámetro | Valor | Motivo |
| --- | --- | --- |
| Tipo | Standard (no FIFO) | Throughput sin límite por grupo. El orden no importa porque el consumidor decide por estado |
| `ReceiveMessageWaitTimeSeconds` | 20 | Long polling: menos llamadas vacías y menos costo |
| `MaxNumberOfMessages` | 10 | Lotes por llamada |
| `VisibilityTimeout` | 60 s | Mayor que el peor caso de procesamiento (timeout de pago 2 s más reintentos) |
| `maxReceiveCount` (redrive) | 5 | Luego a la DLQ para análisis y *redrive* manual |
| Retención | 4 días (cola), 14 días (DLQ) | Margen para recuperar incidentes |
| Cifrado | SSE-KMS | Datos en reposo |

FIFO con `MessageGroupId = eventId` también evitaría carreras, pero serializa cada evento: justo el evento popular se procesaría de a un mensaje. Las escrituras condicionales ya resuelven la concurrencia, así que se prefiere Standard.

**Contrato del mensaje** (`OrderPlaced`, JSON versionado)

```json
{ "messageId": "uuid", "type": "OrderPlaced", "version": 1,
  "occurredAt": "2026-10-24T19:00:00Z",
  "data": { "orderId": "...", "eventId": "...", "quantity": 2 } }
```

Atributos de mensaje: `traceparent` (W3C) para continuar la traza distribuida y `correlationId`. El mensaje no lleva datos personales ni montos: solo identificadores.

**Consumidor reactivo (esqueleto)**

```java
// Single long-lived subscription, started once by an ApplicationRunner and disposed on shutdown.
Disposable listener = Flux.defer(this::receiveBatch)                 // long polling
    .repeat()
    .flatMap(message -> processOrderUseCase.process(toCommand(message))
            .then(deleteMessage(message))                              // delete only on completion
            .onErrorResume(error -> sqsErrorHandler.handle(error, message)), // delete or leave it
        maxConcurrency)                                                // bounded in-flight messages
    .subscribe();
```

Esta es la única suscripción manual del sistema: el flujo de larga vida del listener, iniciado una sola vez al arrancar y cerrado al apagar con su `Disposable`. El `SqsErrorHandler` decide por tipo de error: con `BusinessException` borra el mensaje; con `TechnicalException` reintentable lo deja para que SQS lo reentregue y, tras 5 intentos, vaya a la DLQ.

**Política de reintentos**

| Error | Estrategia |
| --- | --- |
| Throttling de DynamoDB, timeouts de red, 5xx de AWS | `Retry.backoff(3, 100 ms).maxBackoff(2 s).jitter(0.5)` en el adaptador |
| `ConditionalCheckFailed` por versión | Releer y reevaluar una vez; si el estado ya cambió, terminar sin efectos |
| `ConditionalCheckFailed` por inventario | Sin reintento: es una respuesta de negocio (409) |
| Pasarela lenta o caída | Timeout 2 s + circuit breaker (Resilience4j); con el circuito abierto, el mensaje vuelve a la cola |
| Mensaje malformado | Sin reintento; log estructurado y DLQ |

**API** (prefijo `/api/v1`, JSON). Las respuestas siguen un formato estándar: éxito como `{"meta": {"code", "message"}, "data": ...}` y error como `{"errors": [{"reason", "code", "message", "domain"}]}`. Un único `GlobalErrorWebExceptionHandler` convierte las excepciones en respuestas.

| Método y ruta | Respuesta | Notas |
| --- | --- | --- |
| `POST /events` | 201 `Mono<EventResponse>` | Rol admin |
| `GET /events` | 200 `Flux<EventResponse>` | Paginado por `cursor` y `limit` |
| `GET /events/{eventId}` | 200 / 404 |  |
| `GET /events/{eventId}/availability` | 200 `{available, reserved, sold, complimentary}` | RF7 |
| `GET /events/{eventId}/availability/stream` | `text/event-stream` | SSE: emite solo cuando cambia (`distinctUntilChanged`), con *heartbeat* |
| `POST /orders` | 202 `{orderId, status, expiresAt}` / 400 / 409 / 422 / 429 | Header `Idempotency-Key` obligatorio |
| `GET /orders/{orderId}` | 200 `{orderId, status, tickets[], expiresAt}` | Solo el dueño de la orden (o admin) |
| `POST /events/{eventId}/complimentary` | 201 | Rol admin, auditado con el actor |
| `POST /webhooks/payments` | 200 rápido | Firma HMAC; encola y responde |

**Catálogo de errores.** Prefijos `TKS` (éxito), `TKB` (negocio, `BusinessException`) y `TKT` (técnico, `TechnicalException`). El cliente decide siempre por `code`, nunca por el texto.

| Código | Error | HTTP |
| --- | --- | --- |
| TKB0001 | `EVENT_NOT_FOUND` | 404 |
| TKB0002 | `ORDER_NOT_FOUND` (también si la orden es de otro cliente) | 404 |
| TKB0003 | `TICKETS_SOLD_OUT` | 409 |
| TKB0004 | `TICKET_LIMIT_EXCEEDED` | 422 |
| TKB0005 | `IDEMPOTENCY_KEY_REUSED` | 422 |
| TKB0006 | `REQUEST_IN_PROGRESS` | 409 |
| TKB0007 | `EVENT_NOT_ACTIVE` | 409 |
| TKT0001 a TKT0004 | Validación, JSON inválido, body vacío, falta `Idempotency-Key` | 400 |
| TKT0005 a TKT0007 | DynamoDB, SQS, pasarela de pagos (reintentables en el worker) | 500 / 503 |
| TKT0009 | `UNEXPECTED_ERROR` | 500 |

Los adaptadores traducen todo error de AWS a `TechnicalException`, salvo el fallo de una escritura condicional, que es una respuesta de negocio y se traduce a `BusinessException` (por ejemplo, `TICKETS_SOLD_OUT`). En el worker, la clase de error decide si el mensaje se borra o se reintenta, con una clasificación explícita de errores reintentables y definitivos. Por la regla de codificación C012, TechnicalException y su catálogo viven en infrastructure/helpers/commons, fuera del dominio; el dominio solo conoce BusinessException.

**Disponibilidad en tiempo real.** El stream SSE se comparte por evento dentro de cada instancia: un sondeo cada 1 s con `Flux.interval(...).flatMap(read).distinctUntilChanged().share()`. Mil clientes mirando el mismo evento generan una lectura por segundo, no mil. Funciona igual con varias réplicas sin infraestructura extra. En producción se puede alimentar desde DynamoDB Streams para bajar la latencia.

**Disponibilidad de varios eventos (RF7).** `GET /api/v1/events/availability?ids=a,b,c` devuelve un `Flux` con un elemento por evento, leído con `BatchGetItem` en dos llamadas, una a events para saber cuántas porciones tiene cada evento y otra a inventory (máximo 100 ids por petición). Cada elemento suma los shards del inventario y reporta `available`, `reserved`, `sold` y `complimentary`. Las reservas vencidas lógicamente pero aún no barridas se cuentan como reservadas durante, a lo sumo, 30 s: la disponibilidad puede quedar subestimada ese tiempo, nunca sobrestimada.

**Respuesta de `GET /orders/{orderId}` (RF4).** Dos niveles: el `status` de la orden cuenta su ciclo de vida y `tickets[].status` usa el vocabulario exacto del enunciado.

```json
{
  "orderId": "9d8e7f6a-5b4c-4d3e-8f2a-1b0c9d8e7f6a",
  "eventId": "b2c4e6f8-1a3b-4c5d-8e9f-0a1b2c3d4e5f",
  "status": "EXPIRED",
  "expiresAt": "2026-10-26T14:40:00Z",
  "tickets": [
    { "ticketId": "t-01", "status": "AVAILABLE" },
    { "ticketId": "t-02", "status": "AVAILABLE" }
  ]
}
```

| `status` de la orden | `tickets[].status` |
| --- | --- |
| `RESERVED` | `RESERVED` |
| `PENDING_CONFIRMATION` | `PENDING_CONFIRMATION` |
| `SOLD` | `SOLD` |
| `COMPLIMENTARY` | `COMPLIMENTARY` |
| `EXPIRED` o `CANCELLED` | `AVAILABLE` (volvieron al inventario) |

## 8. Seguridad

**Resuelve:** RNF-18, RNF-19

La defensa principal contra reintentos maliciosos y abuso es la misma que protege la consistencia: idempotencia obligatoria y límites aplicados dentro de la transacción, no en memoria. Un atacante que repite peticiones no obtiene más entradas, y uno que reparte peticiones entre réplicas tampoco.

| Amenaza | Control |
| --- | --- |
| Reintentos maliciosos o doble clic | `Idempotency-Key` obligatorio en `POST /orders`. Se guarda el hash del body: misma llave y mismo body devuelven la respuesta original; misma llave con otro body devuelve 422. TTL de 24 h |
| Acaparamiento y reventa (*scalping*) | Máx. 6 entradas por orden y por cliente por evento, verificado en la misma transacción (`customer_event_limits`) |
| Agotar inventario con reservas que nunca se pagan | Reserva de 10 min con liberación automática, límite de reservas activas por cliente y rate limit |
| Abuso de recursos / DoS | WAF con reglas de tasa por IP y Bot Control; throttling de API Gateway por ruta; rate limiter por cliente en la app; tamaño máximo de body; timeouts en todo |
| Webhook falsificado o reenviado | Firma HMAC-SHA256 del body con secreto compartido, comparación en tiempo constante, ventana de 5 min sobre `X-Timestamp` y deduplicación por `eventId` del proveedor |
| Acceso a órdenes ajenas (IDOR) | `GET /orders/{id}` compara `customerId` del JWT con el de la orden; `orderId` es un UUID aleatorio, no secuencial |
| Mensajes envenenados | Validación de esquema en el consumidor; sin reintento; DLQ con alarma |
| Datos sensibles en logs | Logs estructurados sin PII ni tokens; enmascaramiento en el *helper* de logging |

**Cómo funciona la idempotencia**

Repetir una operación produce el mismo resultado que hacerla una vez: un reintento nunca crea una segunda orden, un segundo descuento ni un segundo cobro. Se aplica en tres puntos, porque en los tres puede llegar algo repetido.

![idempotencia en POST /orders · 3 decisiones, 3 respuestas](diagrams/idempotency-flow.png)

El cliente genera la `Idempotency-Key` (un UUID) una sola vez por intento de compra y la reenvía igual en cada reintento. La llave se maneja en dos fases. Primero se reclama (estado IN\_PROGRESS con un lease de 10 s) y después se cierra (COMPLETED, con el resultado) dentro de la misma transacción que la reserva. Por eso no puede existir una reserva sin su llave, ni dos reservas con la misma llave. El reclamo previo permite responder "en curso" (TKB0006) a un reintento simultáneo y guardar también los rechazos de negocio para repetirlos idénticos. Liberar o cerrar la llave exige que siga IN\_PROGRESS y con el mismo token, así que un timeout después de confirmar nunca borra una llave ya completada. Un error técnico la libera para que el reintento vuelva a intentar.

| Punto | Qué puede llegar repetido | Llave | Cómo se detecta |
| --- | --- | --- | --- |
| API (`POST /orders`) | Reintento del cliente por timeout, doble clic, ataque de repetición | `customerId` + `Idempotency-Key` | `Put` condicional `attribute_not_exists` dentro de la transacción de reserva |
| Worker (colas SQS) | El mismo mensaje entregado dos veces (at-least-once) | `orderId` | El worker mira el estado actual de la orden: si ya avanzó, no hace nada |
| Pago y webhook | Caída a mitad del cobro; la pasarela reenvía el webhook | `orderId` ante la pasarela; `eventId` del proveedor en el webhook | La pasarela no cobra dos veces la misma llave; el webhook descarta un `eventId` ya registrado |

**Tres situaciones típicas**

- **Timeout y reintento:** la primera petición sí reservó, pero la respuesta se perdió. El reintento encuentra la llave y devuelve el mismo `orderId`; el inventario no se toca.
- **Doble clic simultáneo:** las dos peticiones llegan a la vez y ninguna ve la llave al leer. Ambas intentan la transacción, pero solo una gana la condición `attribute_not_exists`. La perdedora vuelve a leer y devuelve la respuesta de la ganadora.
- **Misma llave con otro body** (por ejemplo, cambiar la cantidad): se rechaza con 422. Reutilizar la llave para otra compra suele ser un error del cliente o un intento de abuso.

**Especificación publicada de la idempotencia** (alineada con el borrador IETF del header `Idempotency-Key`, con Stripe y con la guía de AWS; va también en el README y el OpenAPI)

| Regla | Valor |
| --- | --- |
| Métodos | Solo `POST /orders`. `GET`, `PUT` y `DELETE` ya son idempotentes por definición |
| Formato de la llave | UUID v4 de 36 caracteres, generado por el cliente (`crypto.randomUUID()`, `UUID.randomUUID()`). Se valida antes de cualquier consulta a la base de datos |
| Alcance | Llave compuesta `customerId` (del JWT) + `Idempotency-Key`. Nadie puede adivinar ni reutilizar la llave de otro cliente |
| Huella del contenido | SHA-256 de `eventId` y `quantity`; nunca datos personales |
| Vigencia | 24 h (TTL de DynamoDB). Pasado ese tiempo, la misma llave crea una compra nueva |
| Qué se guarda | El resultado definitivo (orden aceptada o rechazo de negocio); el cierre de una compra aceptada va en la misma transacción que la reserva |
| Qué no se guarda | Rechazos de validación (400) y `SOLD_OUT`: si luego se liberan entradas, el reintento con la misma llave puede comprar |

| Situación | Respuesta |
| --- | --- |
| Falta el header o no es un UUID | 400, código TKT0004 (MISSING\_IDEMPOTENCY\_KEY) |
| Reintento de una compra ya completada | La respuesta original (202, mismo `orderId`) más el header `Idempotent-Replayed: true` |
| Misma llave con otro contenido | 422 `TKB0005`; el cliente debe corregir la petición |
| Reintento mientras la original sigue en curso | 409 `TKB0006`; el cliente reintenta tras unos segundos sin cambiar nada |

La API usa 409 en dos casos distintos: `TKB0006` (petición en curso) y `TKB0003` (agotado). El cliente los distingue por el campo `code` de la respuesta de error. Con la transacción atómica, el 409 "en curso" casi nunca ocurre: solo en la ventana de milisegundos entre la escritura y la respuesta. Aun así se documenta, porque el estándar de idempotencia lo pide.

**Ejemplo de petición**

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 7f3a9c2e-5b1d-4e8a-9f21-3c6d8e0b4a17" \
  -d '{"eventId": "b2c4e6f8-1a3b-4c5d-8e9f-0a1b2c3d4e5f", "quantity": 2}'
```

**Alternativas evaluadas para la idempotencia**

| Alternativa | Por qué no como mecanismo principal |
| --- | --- |
| Llave derivada del contenido (hash de cliente + evento + cantidad) | Bloquearía una segunda compra legítima e idéntica. Se usa solo en el worker, donde la llave natural es el `orderId` |
| `orderId` generado por el cliente con `PUT /orders/{orderId}` | Es idempotente por definición, pero cede al cliente la generación de identificadores y se aleja del contrato habitual de compra |
| Token emitido por el servidor al abrir el checkout | Agrega una llamada y estado extra. Encaja si más adelante se suma una sala de espera virtual |
| Deduplicación de SQS FIFO | Solo aplica a mensajes, con ventana corta; no protege el endpoint HTTP |

**Secretos y credenciales**

- En AWS no hay llaves de acceso: los contenedores asumen un IAM Task Role con permisos mínimos por servicio. La API no puede leer la DLQ; el worker no puede crear eventos.
- Los secretos de terceros (llave HMAC del webhook, credenciales de la pasarela) viven en Secrets Manager con rotación. Se leen al arrancar y se cachean con refresco periódico, igual que el patrón `install.sh` del entorno de referencia pero contra el servicio real.
- En local, LocalStack recibe credenciales ficticias (`test`/`test`) desde variables de entorno. El token de LocalStack, si se usa, va en un `.env` fuera del control de versiones (incluido en `.gitignore`).
- Ningún secreto en `application.yaml`, imágenes ni Terraform. Las variables sensibles de Terraform se marcan `sensitive = true` y el estado remoto se cifra.
- Cifrado en tránsito (TLS 1.2 o superior en el borde, endpoints HTTPS de AWS) y en reposo (KMS con llave administrada por el cliente para DynamoDB, SQS y logs).
- Cadena de suministro: imagen *distroless* sin shell, usuario no root, escaneo en ECR y análisis de dependencias en el pipeline.

## 9. Arquitectura objetivo en AWS

**Resuelve:** RNF-16, RNF-17, RNF-19, RNF-20, RNF-21

En producción, los dos servicios corren en ECS Fargate en subredes privadas, detrás de CloudFront, WAF y API Gateway, y hablan con DynamoDB y SQS por VPC endpoints, sin salir a internet. Todo se define en Terraform, con una cuenta de AWS por ambiente.

![Arquitectura en AWS](diagrams/aws-deployment.png)

**Decisiones de plataforma**

| Decisión | Elección | Alternativa descartada y por qué |
| --- | --- | --- |
| Cómputo | ECS Fargate | EKS: más operación para dos servicios. Lambda: arranque en frío de JVM y conexiones SSE largas no encajan |
| Base de datos | DynamoDB on-demand | Aurora: escala de escritura vertical y bloqueos de filas en picos. Provisioned + autoscaling reacciona en minutos, tarde para un lanzamiento; se puede pre-calentar si el evento es conocido |
| Mensajería | SQS Standard + DLQ | Kafka/MSK: sobredimensionado para colas de trabajo punto a punto |
| Barrido | EventBridge Scheduler → cola `reservation-sweep` | `@Scheduled` reactivo + lock con escritura condicional (se usa en local): en AWS se evita mantener el lock |
| Borde | CloudFront + WAF + API Gateway HTTP API con JWT authorizer | ALB público: sin autorización JWT ni *throttling* gestionados |
| Salida a AWS | VPC endpoints | NAT Gateway: costo por GB y superficie de salida a internet |

**Ruta especial para el stream SSE.** API Gateway HTTP API corta las integraciones a los 30 s y no está pensado para conexiones largas, así que la ruta `/availability/stream` no pasa por él. CloudFront la envía directo al ALB interno como *VPC origin*. El ALB tiene el tiempo de inactividad ampliado (hasta 4 000 s) y WAF sigue protegiendo la ruta en CloudFront. La señal de vida del stream, cada 15 s y configurable, mantiene la conexión abierta por debajo de cualquier límite de inactividad intermedio. El token del cliente se valida en la aplicación para esa ruta, porque no pasa por el autorizador de API Gateway.

**Terraform**

```text
deployment/terraform/
├── modules/
│   ├── network/        # VPC 3 AZ, subredes privadas, endpoints, security groups
│   ├── data/           # tablas DynamoDB, GSIs, PITR, KMS
│   ├── messaging/      # colas, DLQs, redrive, políticas
│   ├── ecs-service/    # task definition, task role, autoscaling (reutilizado por api y worker)
│   ├── edge/           # API Gateway, VPC Link, WAF, CloudFront
│   └── observability/  # dashboards, alarmas, log groups con retención
└── envs/{dev,qa,prod}/ # main.tf + terraform.tfvars + backend S3 (cifrado, versionado, lock)
```

- **Aislamiento de ambientes:** cuentas separadas bajo AWS Organizations con SCPs. El *blast radius* de un error en dev no alcanza prod.
- **Red:** solo el ALB interno recibe tráfico, y solo desde el VPC Link. Las tareas aceptan tráfico únicamente del security group del ALB. Políticas de endpoint restringen a las tablas y colas propias.
- **Escalabilidad:** la API escala por CPU y peticiones por destino; el worker por `ApproximateNumberOfMessagesVisible` dividido entre tareas. Mínimo 2 tareas en 2 AZ en prod.
- **Pipeline:** `terraform fmt`, `validate`, `tflint` y `checkov` en cada PR; `plan` como comentario; `apply` con aprobación en prod.
- **Gobierno:** etiquetas obligatorias (`app`, `env`, `owner`, `cost-center`), CloudTrail, AWS Config y GuardDuty a nivel de organización.

**Observabilidad**

| Señal | Qué se mide | Alarma |
| --- | --- | --- |
| Métricas de negocio (Micrometer) | `orders.reserved`, `orders.sold`, `orders.expired`, `orders.sold_out`, tasa de conversión reserva → venta | Caída brusca de ventas con tráfico normal |
| API | p50/p95/p99 por ruta, tasa de 5xx y 429 | p99 > objetivo o 5xx > 1 % durante 5 min |
| Colas | `ApproximateAgeOfOldestMessage`, mensajes visibles, DLQ | DLQ > 0; edad > 60 s |
| DynamoDB | Throttles, latencia, `ConditionalCheckFailed` por tabla | Throttles sostenidos |
| Trazas | OpenTelemetry (ADOT → X-Ray), propagadas por SQS con `traceparent` | — |
| Logs | JSON con `traceId`, `orderId`, `eventId`; retención 30 días en caliente, luego a S3 | — |

**Costos.** Todo es pago por uso: con tráfico bajo el costo es casi nulo y en un pico crece con el uso real. Las palancas principales son estas:

- Worker en Fargate Spot con una base On-Demand.
- VPC endpoints en lugar de NAT Gateway.
- Long polling en SQS.
- TTL en DynamoDB para idempotencia y límites.
- Retención acotada de logs.
- AWS Budgets con alertas por ambiente.

## 10. Entorno local, pruebas y evolución

**Resuelve:** RF2, RF6 (demostración) · RNF-04, RNF-05, RNF-06, RNF-12, RNF-13, RNF-14, RNF-22

### 10.1 Entorno local

`docker compose up` levanta tres servicios. Replica el entorno de referencia: un solo LocalStack emula todos los servicios de AWS y unos scripts crean los recursos al arrancar. Agrega dos mejoras: versión fijada y recursos creados automáticamente, sin pasos manuales.

| Servicio | Imagen | Rol |
| --- | --- | --- |
| `localstack` | `localstack/localstack` 4.x fijada (`SERVICES=dynamodb,sqs,secretsmanager`) | DynamoDB, colas con DLQ y secretos. Los scripts de `deployment/localstack/init/ready.d/` crean tablas, GSIs, colas y secretos al arrancar; healthcheck sobre `/_localstack/health` |
| `ticketing-api` | Imagen de la app, rol `api` | Puerto por variable; `depends_on` LocalStack sano |
| `ticketing-worker` | Misma imagen, rol `worker` | Consumidores y barrido (desde la feature 002) |

Desde el 23 de marzo de 2026, las versiones nuevas de LocalStack requieren cuenta y *auth token*, incluido el plan gratuito ([LocalStack](https://blog.localstack.cloud/localstack-single-image-next-steps/)). Por eso la imagen se fija en una versión 4.x anterior a ese cambio, verificada en el setup de la feature 001. Si el evaluador prefiere la última versión, basta con `LOCALSTACK_AUTH_TOKEN` en `.env`. La imagen 0.12.7 del entorno de referencia es demasiado antigua para usarla aquí. La aplicación se conecta con el mismo SDK que en AWS; solo cambia el endpoint (`AWS_ENDPOINT_URL`) y usa credenciales ficticias en local.

**Demostración de todos los flujos en local.** Si el simulador aprobara siempre al instante, ninguna reserva llegaría a vencer y RF2 y RF6 no se podrían mostrar. Por eso el comportamiento es configurable:

| Variable de entorno | Valores | Para qué sirve |
| --- | --- | --- |
| `PAYMENT_SIMULATOR_MODE` | `APPROVE` (despliegue), `DECLINE`, `PENDING` | `PENDING` deja la orden en `PENDING_CONFIRMATION` esperando un webhook que nunca llega, así se ve vencer; `DECLINE` muestra la cancelación |
| `RESERVATION_TTL` | `PT10M` en despliegue; `PT1M` en local | Ver la expiración en un minuto en lugar de diez |
| `SWEEPER_INTERVAL` | `PT30S` en despliegue; `PT10S` en local | Ver la liberación física rápido |

La colección de Postman incluye un flujo "Reserva que vence": crear un evento de 5 entradas, reservar 2 con el simulador en `PENDING` y consultar la orden y la disponibilidad. Tras el TTL, la orden aparece `EXPIRED` con sus entradas `AVAILABLE`, y la disponibilidad vuelve a 5. También incluye los flujos de venta aprobada, pago rechazado, agotado (409), reintento idempotente y webhook firmado.

### 10.2 Estrategia de pruebas (cobertura mínima 90 %, verificada con JaCoCo en el build)

- **Casos de uso:** JUnit 5 + Mockito + `StepVerifier` sobre cada rama (reserva exitosa, agotado, límite excedido, replay idempotente, duplicado, expiración, carrera perdida).
- **Dominio:** pruebas puras de la máquina de estados, transición por transición, incluidas las inválidas.
- **Entry points:** `WebTestClient` sobre router y handlers (códigos HTTP, formato de error y códigos, header de idempotencia, SSE con `StepVerifier`); listener SQS con cliente simulado, verificando que solo borra al completar.
- **Concurrencia:** 200 reservas en paralelo (`Flux.range(...).flatMap(..., 200)`) contra un evento de capacidad 50. Se verifica que exactamente 50 ganan, 150 reciben `SOLD_OUT` y la invariante de contadores se mantiene. Corre contra un repositorio en memoria con semántica condicional atómica y, en integración, contra LocalStack con Testcontainers.
- **Adaptadores:** pruebas de integración con Testcontainers (módulo LocalStack).
- **Opcional, valor diferencial:** pruebas de mutación con PIT (como el proyecto de referencia) y prueba de carga con k6 o Gatling para respaldar los números de latencia.

### 10.3 Alternativas consideradas

| Alternativa | Por qué no (por ahora) |
| --- | --- |
| Reservar en el consumidor, no en la API | El usuario recibe un `orderId` que puede terminar agotado; más *polling* y peor experiencia |
| Redis con scripts Lua para el inventario | Muy rápido, pero agrega un segundo almacén que mantener consistente con DynamoDB. Queda como opción si los shards no alcanzan |
| Una entrada pre-creada por asiento | Necesario para asientos numerados; para admisión general multiplica escrituras sin beneficio |
| Outbox con DynamoDB Streams + EventBridge Pipes | Es la versión de producción del outbox liviano; en local agrega complejidad sin cambiar el diseño |
| Mensajes con retardo de 10 min (SQS `DelaySeconds` hasta 15 min) para expirar | Más preciso que el barrido; se puede sumar. El enunciado pide un proceso periódico, que además cubre fallos del envío |
| TTL de DynamoDB para expirar | El borrado por TTL puede tardar horas; no sirve como mecanismo de negocio |

### 10.4 Limitaciones y qué cambiaría en producción

- **Sala de espera virtual** frente a lanzamientos masivos (cola de admisión en el borde). Protege el backend y da equidad a los compradores. Es la mejora de mayor impacto.
- **Outbox completo** con Streams + Pipes en lugar del re-publicado por barrido.
- **Asientos numerados:** ítem por asiento con escritura condicional `status = AVAILABLE` y mapa del recinto.
- **Dividir en microservicios** (Catalog, Inventory, Ordering) solo cuando equipos o ritmos de cambio lo justifiquen. Las tablas por contexto ya lo permiten.
- **Reportes contables** desde `order_audit` exportado a S3 y consultado con Athena, fuera del camino transaccional.
- **Pasarela real:** reembolsos automáticos en la carrera pago vs. vencimiento y conciliación diaria.
- **SLOs formales** con presupuesto de error, y pruebas de caos (inyección de *throttling*, caída del worker).

### 10.5 Plan de implementación

- [ ] Generar el proyecto con el scaffold y subir a Java 25 y Spring Boot 4.x (validar compatibilidad del plugin)
- [ ] Dominio: value objects, agregados, máquina de estados con vencimiento lógico, y pruebas
- [ ] Casos de uso con puertos y pruebas con mocks
- [ ] Adaptador DynamoDB (transacciones, GSI disperso, shards, `BatchGetItem`) y pruebas de integración
- [ ] Adaptadores SQS (sender y listener) con DLQ
- [ ] API WebFlux, manejador global de errores, idempotencia, disponibilidad por lote, SSE
- [ ] Barrido con lock condicional y re-publicado (outbox liviano)
- [ ] Simulador de pagos con modos `APPROVE` / `DECLINE` / `PENDING`, TTL e intervalo configurables
- [ ] Seguridad: JWT, rate limiting, webhook HMAC
- [ ] `docker-compose.yml`, scripts de LocalStack, Dockerfile
- [ ] Prueba de concurrencia y cobertura de 90 % o más
- [ ] README, colección Postman (incluye el flujo "Reserva que vence") y diagramas
- [ ] Terraform (módulos y ambiente dev)

### 10.6 Contenido del README (RNF-12)

1. Descripción breve de la solución y diagrama de microservicios y colas.
2. Requisitos previos: Docker, Java 25, Gradle 9.2.1 o superior y, si se usa la última versión de LocalStack, su token en `.env`.
3. Instalación y configuración: variables de entorno (sin valores por defecto), roles `api` y `worker`, modos del simulador.
4. Comandos: `docker compose up -d`, verificación de salud, logs y `docker compose down -v`.
5. Decisiones de arquitectura: resumen de este RFC con enlace a él.
6. Ejemplos de uso con `curl` de cada endpoint y referencia a la colección de Postman.
7. Cómo ejecutar las pruebas y ver el reporte de cobertura.

### 10.7 Estado de implementación

El repositorio se construye por features con Spec-Driven Development. Cada una se entrega con su PR, pruebas automatizadas y cobertura mínima del 90 %.

| Feature | Alcance | Estado |
| --- | --- | --- |
| 001 | Eventos, inventario por porciones y disponibilidad (individual, por lote y SSE) | Entregada |
| 002 | Compra con reserva atómica, idempotencia, cola con DLQ, worker, simulador de pagos y consulta de la orden | Entregada |
| 003 | Barrido de reservas vencidas con lock distribuido y entradas de cortesía | Entregada |
| 003b | Modos de demostración del simulador (lento, aleatorio, caído) | Siguiente |
| 004 | Seguridad y resiliencia: JWT, rol de administrador, rate limiting, webhook firmado, circuit breaker | Siguiente |
| 005 | Infraestructura en AWS con Terraform | Siguiente |

Las secciones 8 y 9 describen el diseño completo de las features 004 y 005, que se incorporan después de la primera entrega.

### Fuentes

- Enunciado: "Plataforma de Procesamiento de Eventos de Ticketing", prueba técnica para desarrolladores 2026 (PDF entregado por Nequi)
- [Scaffold Clean Architecture — Bancolombia](https://bancolombia.github.io/scaffold-clean-architecture/docs/intro/)
- [Scaffold: Getting Started (plugin 5.0.2)](https://bancolombia.github.io/scaffold-clean-architecture/docs/getting-started)
- [Scaffold: Generate Driven Adapter](https://bancolombia.github.io/scaffold-clean-architecture/docs/tasks/generate-driven-adapter)
- [Scaffold: Generate Entry Point](https://bancolombia.github.io/scaffold-clean-architecture/docs/tasks/generate-entry-point)
- [LocalStack — Single image next steps](https://blog.localstack.cloud/localstack-single-image-next-steps/)

* [IETF — The Idempotency-Key HTTP Header Field (draft 07, trabajo en progreso)](https://www.ietf.org/archive/id/draft-ietf-httpapi-idempotency-key-header-07.html)
* [Stripe — Idempotent requests](https://docs.stripe.com/api/idempotent_requests)
* [AWS Builders' Library — Making retries safe with idempotent APIs](https://builder.aws.com/content/3Ev0BENTyBr0XxzRk5FDZzgNYos/making-retries-safe-with-idempotent-apis)
* [Powertools for AWS Lambda (Java) — Idempotency](https://docs.aws.amazon.com/powertools/java/latest/utilities/idempotency/)
