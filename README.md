# Ticketing Platform

Backend reactivo de venta de entradas para eventos. Expone una API REST para crear y consultar
eventos y su disponibilidad, garantiza cero sobreventa con escrituras condicionales en DynamoDB y
procesa las órdenes de forma asíncrona.

## Stack

Java 25, Spring Boot 4.1 con WebFlux, AWS SDK v2 asíncrono (DynamoDB y SQS), Gradle 9 y la
estructura de Clean Architecture del plugin `co.com.bancolombia.cleanArchitecture`.

## Requisitos

- Docker con Docker Compose.
- JDK 25 (Temurin) solo si se compila fuera de Docker.

## Ejecución local

```bash
cd deployment
cp .env.example .env
docker compose up
```

Levanta LocalStack (DynamoDB, SQS y Secrets Manager) con sus recursos y la API. Los pasos
detallados están en [GETTINGSTARTED.md](GETTINGSTARTED.md).

## API

Base: `http://localhost:8080/api/v1`. Las respuestas exitosas usan `{"meta": {...}, "data": ...}` y
los errores `{"errors": [{"reason", "code", "message", "domain"}]}` con un código estable del
catálogo (`TKB`, `TKT`).

```bash
BASE=http://localhost:8080/api/v1

# Crear un evento (201, TKS0002)
curl -s -X POST $BASE/events -H 'Content-Type: application/json' \
  -d '{"name":"Rock al Parque 2027","venue":{"name":"Movistar Arena","city":"Bogota"},"date":"2027-03-20T01:00:00Z","capacity":500}'

# Consultar un evento (200)
curl -s $BASE/events/{eventId}

# Listar eventos activos con fecha futura, paginados (limit de 1 a 100; cursor opaco de data.nextCursor)
curl -s "$BASE/events?limit=20"

# Disponibilidad de un evento (lectura fuerte)
curl -s $BASE/events/{eventId}/availability

# Disponibilidad de hasta 100 eventos; los ids inexistentes se omiten
curl -s "$BASE/events/availability?ids={eventId1},{eventId2}"

# Disponibilidad en vivo (SSE): estado inicial, luego solo cambios, keepalive cada 15 s
curl -N $BASE/events/{eventId}/availability/stream
```

## Build y pruebas

```bash
./gradlew build
```

Incluye `validateStructure`, el `ArchitectureTest`, las pruebas unitarias y de integración (con
LocalStack en Testcontainers, necesitan Docker), la verificación de paridad de variables, el gate de
cobertura combinada de JaCoCo (90 % de líneas) y las pruebas de mutación, que no bloquean el build.

La prueba de latencia (p99 < 100 ms) corre aparte: `./gradlew performanceTest`.

## Seguridad de dependencias

Las dependencias de Gradle están bloqueadas en los archivos `gradle.lockfile` de cada módulo, que
Trivy usa para el análisis. Después de cambiar una versión se regeneran con:

```bash
./gradlew resolveAndLockAll --write-locks
```

Escaneo del código y de la imagen, sin instalar nada en la máquina (deben terminar sin
vulnerabilidades HIGH ni CRITICAL):

```bash
docker run --rm -v "$PWD":/src aquasec/trivy:0.76.1 fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 /src
docker build -f deployment/Dockerfile -t ticketing-platform:local .
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.76.1 image --severity HIGH,CRITICAL --exit-code 1 ticketing-platform:local
```

Si una vulnerabilidad no tiene versión corregida, la excepción se documenta en `.trivyignore` con el
CVE, el motivo y la fecha de revisión.

## Documentación

- Diseño funcional y técnico: [docs/rfc/RFC-001-ticketing.md](docs/rfc/RFC-001-ticketing.md).
- Cambios por versión: [CHANGELOG.md](CHANGELOG.md).
- Cómo contribuir: [CONTRIBUTING.md](CONTRIBUTING.md).
