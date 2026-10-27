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

## Build y pruebas

```bash
./gradlew build
```

Incluye `validateStructure`, las pruebas unitarias, la cobertura de JaCoCo y las pruebas de
mutación.

## Documentación

- Diseño funcional y técnico: [docs/rfc/RFC-001-ticketing.md](docs/rfc/RFC-001-ticketing.md).
- Cambios por versión: [CHANGELOG.md](CHANGELOG.md).
- Cómo contribuir: [CONTRIBUTING.md](CONTRIBUTING.md).
