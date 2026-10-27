# Entorno local

Levanta con Docker Compose un LocalStack fijado por digest (DynamoDB, SQS y Secrets Manager), la
API y el worker. Funciona también con `podman-compose`.

## Servicios

| Servicio | Imagen | Puerto | Qué hace |
| --- | --- | --- | --- |
| `localstack` | `localstack/localstack:4.14.0` | 4566 | DynamoDB, SQS y Secrets Manager. Crea las tablas con `localstack/init/ready.d/01-dynamodb.sh` al arrancar |
| `ticketing-api` | Se construye con `deployment/Dockerfile` | `APP_PORT` (8080) | API con `APP_ROLE=api` y perfil `local`. Arranca cuando LocalStack está `healthy` |
| `ticketing-worker` | La misma imagen | `WORKER_PORT` (8081) → `APP_PORT` | Worker con `APP_ROLE=worker`: consume `order-placed`, cobra con el simulador y republica las órdenes pendientes. Solo expone `health` y `prometheus` |

LocalStack crea las colas con `localstack/init/ready.d/02-sqs.sh`: `${SQS_ORDER_PLACED_QUEUE}` (visibilidad
`SQS_VISIBILITY_TIMEOUT_SECONDS`, espera larga de 20 s, retención de 4 días) y su DLQ
`${SQS_ORDER_PLACED_DLQ}` (retención de 14 días), con `maxReceiveCount` `SQS_MAX_RECEIVE_COUNT`.

## Comandos

```bash
cp .env.example .env                                   # una sola vez; .env no se versiona
docker compose up -d --build                           # levanta todo
docker compose ps                                      # estado de los servicios
docker compose logs -f ticketing-api                   # logs de la API
docker compose logs -f ticketing-worker                # logs del worker
docker compose exec localstack awslocal dynamodb list-tables
docker compose exec localstack awslocal sqs list-queues
curl -s localhost:8081/actuator/health                 # salud del worker (WORKER_PORT)
docker compose down -v                                 # apaga y borra los datos
```

El healthcheck de LocalStack espera a que terminen los scripts de `init/ready.d`
(`/_localstack/init/ready`), así la API nunca arranca sin tablas.

## Ver la DLQ

```bash
DLQ=$(docker compose exec localstack awslocal sqs get-queue-url --queue-name order-placed-dlq --query QueueUrl --output text)
docker compose exec localstack awslocal sqs receive-message --queue-url "$DLQ" \
  --message-attribute-names All --max-number-of-messages 10
```

Cada mensaje movido a mano por el worker lleva el atributo `failureCode` con el código del error.

## Cambiar una variable de un solo servicio

Cambiar `.env` recrea también LocalStack, que guarda los datos en memoria. Para cambiar, por
ejemplo, `PAYMENT_SIMULATOR_MODE` solo en el worker, se usa un override temporal (el bloque
`environment` tiene precedencia sobre `env_file`) y se recrea solo ese servicio:

```bash
cat > /tmp/override.yml <<'EOF'
services:
  ticketing-worker:
    environment:
      PAYMENT_SIMULATOR_MODE: DECLINE
EOF
docker compose -f docker-compose.yml -f /tmp/override.yml up -d --no-deps ticketing-worker
docker compose up -d --no-deps ticketing-worker        # vuelve al valor de .env
```

## Problemas comunes

| Síntoma | Causa | Solución |
| --- | --- | --- |
| `Cannot connect to the Docker daemon` | Docker no está en ejecución | Iniciar Docker Desktop o Podman |
| `port is already allocated` en 4566 o 8080 | Otro proceso usa el puerto | Detenerlo o cambiar `APP_PORT` en `.env` |
| LocalStack pide `LOCALSTACK_AUTH_TOKEN` | Se cambió la imagen por una versión `2026.x` o `latest` | Volver a `4.14.0`, la última versión sin cuenta |
| La API no arranca por una variable faltante | `.env` desactualizado frente a `.env.example` | Volver a copiar `.env.example` |
