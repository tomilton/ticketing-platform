# Cómo contribuir

## Flujo

1. Crea una rama desde `main` con el formato `feature/<número>-<nombre>`.
2. Escribe primero las pruebas y después el código.
3. Verifica que `./gradlew build` termina sin errores antes de abrir el PR.

## Commits

Formato `tipo(alcance): descripción`, con el alcance en `snake_case` y una descripción breve en
español. Tipos: `feat`, `fix`, `test`, `refactor`, `docs`, `build` y `chore`.

```text
feat(event_domain): agregado Event e inventario por porciones
```

## Pull requests

- Máximo 200 líneas por PR. Si el cambio es mayor, se divide en PRs apilados: primero el dominio,
  después los casos de uso y al final la infraestructura.
- Cobertura mínima del 90 % y cero code smells.
- Todo el código, el Javadoc y los mensajes de error van en inglés; la documentación, en español.

## Análisis de Sonar antes de cada PR

El análisis corre contra un SonarQube local del perfil `quality`, que no se levanta con el
`docker compose up` normal:

```bash
cd deployment
docker compose --profile quality up -d sonarqube      # http://localhost:${SONAR_PORT}
```

En la interfaz se crea un token de análisis (Mi cuenta → Seguridad). El token nunca se versiona.
Después, desde la raíz del repositorio:

```bash
./gradlew build jacocoMergedReport sonar -Dsonar.host.url=http://localhost:${SONAR_PORT} -Dsonar.token=<token>
```

El PR se abre solo si el proyecto muestra 0 code smells, 0 bugs, 0 vulnerabilidades y una cobertura
del reporte combinado de JaCoCo de al menos 90 %. Al terminar: `docker compose --profile quality down`.

