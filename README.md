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
