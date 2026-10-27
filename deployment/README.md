# Entorno local

Levanta con Docker Compose un LocalStack fijado por digest (DynamoDB, SQS y Secrets Manager) y la
API. Funciona también con `podman-compose`.

## Servicios

| Servicio | Imagen | Puerto | Qué hace |
| --- | --- | --- | --- |
| `localstack` | `localstack/localstack:4.14.0` | 4566 | DynamoDB, SQS y Secrets Manager. Crea las tablas con `localstack/init/ready.d/01-dynamodb.sh` al arrancar |
| `ticketing-api` | Se construye con `deployment/Dockerfile` | `APP_PORT` (8080) | API con `APP_ROLE=api` y perfil `local`. Arranca cuando LocalStack está `healthy` |

## Comandos

```bash
cp .env.example .env                                   # una sola vez; .env no se versiona
docker compose up -d --build                           # levanta todo
docker compose ps                                      # estado de los servicios
docker compose logs -f ticketing-api                   # logs de la API
docker compose exec localstack awslocal dynamodb list-tables
docker compose down -v                                 # apaga y borra los datos
```

El healthcheck de LocalStack espera a que terminen los scripts de `init/ready.d`
(`/_localstack/init/ready`), así la API nunca arranca sin tablas.

## Problemas comunes

| Síntoma | Causa | Solución |
| --- | --- | --- |
| `Cannot connect to the Docker daemon` | Docker no está en ejecución | Iniciar Docker Desktop o Podman |
| `port is already allocated` en 4566 o 8080 | Otro proceso usa el puerto | Detenerlo o cambiar `APP_PORT` en `.env` |
| LocalStack pide `LOCALSTACK_AUTH_TOKEN` | Se cambió la imagen por una versión `2026.x` o `latest` | Volver a `4.14.0`, la última versión sin cuenta |
| La API no arranca por una variable faltante | `.env` desactualizado frente a `.env.example` | Volver a copiar `.env.example` |
