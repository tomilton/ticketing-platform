# Primeros pasos

## 1. Clonar y compilar

```bash
git clone <url-del-repositorio>
cd ticketing-platform
./gradlew build
```

El archivo `gradle/wrapper/gradle-wrapper.jar` no se versiona. Si falta, genéralo con
`gradle wrapper` o compila con la imagen Docker, que usa la imagen oficial de Gradle.

## 2. Levantar el entorno local

```bash
cd deployment
cp .env.example .env
docker compose up -d
docker compose ps
```

LocalStack crea las tablas al arrancar con los scripts de `deployment/localstack/init/ready.d/`.

## 3. Probar la API

```bash
curl -s http://localhost:8080/api/v1/events
```

## 4. Apagar

```bash
docker compose down -v
```
