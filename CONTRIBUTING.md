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
