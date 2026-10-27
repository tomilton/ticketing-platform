# Changelog

Todos los cambios relevantes del proyecto se registran en este archivo.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el proyecto usa
[versionamiento semántico](https://semver.org/lang/es/).

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
