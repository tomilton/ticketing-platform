# Infraestructura en AWS con Terraform

Código de la arquitectura objetivo del [RFC-001 §9](../../docs/rfc/RFC-001-ticketing.md#9-arquitectura-objetivo-en-aws):
ECS Fargate en subredes privadas sin NAT, DynamoDB y SQS por VPC endpoints, y CloudFront con WAF y
API Gateway como única entrada pública. Se valida completo sin una cuenta de AWS; desplegarlo necesita
la cuenta y los prerrequisitos de abajo.

## Estructura

```text
deployment/terraform/
├── .tflint.hcl
├── modules/
│   ├── network/        # VPC en 3 AZ, subredes privadas /20 sin NAT, 7 endpoints, flow logs
│   ├── data/           # 7 tablas on-demand con sus índices y TTL, PITR y clave KMS con rotación
│   ├── messaging/      # cola de órdenes y DLQ (maxReceiveCount 5), KMS, políticas por rol
│   ├── ecs-cluster/    # cluster Fargate con Container Insights, On-Demand y Spot
│   ├── alb/            # ALB interno (idle 4 000 s para el SSE) y security groups
│   ├── ecs-service/    # task definition con las variables de application.yaml, roles y escalado
│   ├── edge/           # API Gateway con VPC Link, CloudFront con VPC origin para el SSE y WAF
│   ├── observability/  # alarmas, SNS cifrado, dashboard y presupuesto
│   └── platform/       # composición de un ambiente con todos los módulos
└── envs/{dev,qa,prod}/ # proveedores, backend S3, variables y terraform.tfvars
```

Los tres ambientes usan la misma composición (`modules/platform`) y solo cambian sus valores en
`terraform.tfvars`: tamaño de las tareas, rangos de escalado, límites del borde, retención, presupuesto
y parámetros de la aplicación. En prod la plataforma exige mínimo 2 tareas por servicio y protección
contra borrado de las tablas y el ALB; un valor menor falla en la validación.

### Decisiones

- **Nombres de los recursos.** Las tablas, los índices y las colas son los de
  `deployment/localstack/init/ready.d/`, con el prefijo del ambiente (`ticketing-prod-orders`).
- **Variables de la aplicación.** `modules/ecs-service/environment.tf` arma las 67 variables de
  `application.yaml` que existen en AWS. `./gradlew verifyEnvironmentParity` falla si falta una o si
  sobra. `AWS_ENDPOINT_URL` solo existe en local, y las credenciales salen del rol de la tarea.
- **Mínimo privilegio.** La API solo publica en la cola y no toca los locks del barrido. El worker
  consume, reenvía y mueve mensajes a la DLQ, y no toca las llaves de idempotencia. Cada rol accede
  solo a sus tablas, sus índices y las dos claves KMS, siempre por ARN.
- **Red.** El ALB interno solo admite el security group del VPC Link y la lista de prefijos de
  CloudFront. Las tareas solo admiten el ALB y solo salen por HTTPS a los endpoints. TLS termina en
  CloudFront y en API Gateway; el salto interno en la VPC privada es HTTP.
- **Barrido.** Corre en el worker con el lock de DynamoDB, igual que en local. EventBridge Scheduler
  queda como evolución (RFC §9).
- **JWT.** El autorizador de API Gateway está activo con el emisor y la audiencia de cada ambiente
  (`edge.jwt` en `terraform.tfvars`); el catálogo, la disponibilidad y el webhook firmado son rutas
  públicas. El secreto HMAC del webhook vive en Secrets Manager y su valor se carga fuera de Terraform.
- **Health check del contenedor.** La imagen `eclipse-temurin:25-jre` no trae curl ni wget, así que el
  chequeo habla HTTP con un socket TCP de bash contra `/actuator/health`. La raíz del contenedor es de
  solo lectura, con un volumen efímero en `/tmp`.

## Validar sin cuenta de AWS

Todo corre en contenedores con versión fijada, igual que en CI (`.github/workflows/terraform.yml`):

| Herramienta | Imagen |
| --- | --- |
| Terraform 1.16.7, proveedor `hashicorp/aws` 6.70 | `hashicorp/terraform:1.16.7` |
| tflint 0.65.0, ruleset aws 0.50.0 | `ghcr.io/terraform-linters/tflint:v0.65.0` |
| checkov 3.3.30 | `bridgecrew/checkov:3.3.30` |
| actionlint 1.7.12 | `rhysd/actionlint:1.7.12` |

Desde `deployment/terraform`:

```bash
TF="docker run --rm -v $PWD:/w -e AWS_EC2_METADATA_DISABLED=true hashicorp/terraform:1.16.7"

# Formato
$TF -chdir=/w fmt -check -recursive

# Pruebas de cada módulo y de cada ambiente (proveedor simulado; los ambientes planifican con su tfvars)
for dir in modules/network modules/data modules/messaging modules/ecs-cluster modules/alb \
           modules/ecs-service modules/edge modules/observability envs/dev envs/qa envs/prod; do
  $TF -chdir=/w/$dir init -backend=false -input=false && $TF -chdir=/w/$dir test
done

# Validación de cada ambiente
for env in dev qa prod; do $TF -chdir=/w/envs/$env validate; done

# Linter y seguridad
docker run --rm -v "$PWD":/data -w /data --entrypoint sh ghcr.io/terraform-linters/tflint:v0.65.0 \
  -c 'tflint --init && tflint --recursive --config /data/.tflint.hcl'
docker run --rm -v "$PWD":/tf -w /tf bridgecrew/checkov:3.3.30 -d . --framework terraform --compact --quiet \
  --skip-path '\.terraform'

# Workflows de CI (desde la raíz del repositorio)
docker run --rm -v "$PWD/../..":/repo -w /repo rhysd/actionlint:1.7.12
```

Cada excepción de checkov lleva su motivo en un comentario `# checkov:skip=` junto al recurso.

## Desplegar en una cuenta

### Prerrequisitos

1. **Cuenta y credenciales** con permisos de administración de la infraestructura (idealmente una
   cuenta por ambiente bajo AWS Organizations).
2. **Bucket de estado** en S3, con versionado y cifrado activados y el acceso público bloqueado. El
   bloqueo usa un archivo de lock en el mismo bucket (`use_lockfile`), sin tabla de DynamoDB.
3. **Repositorio ECR** `ticketing-platform` con la imagen publicada con el tag de `image_tag`:

   ```bash
   aws ecr get-login-password | docker login --username AWS --password-stdin <cuenta>.dkr.ecr.us-east-1.amazonaws.com
   docker build -f deployment/Dockerfile -t <cuenta>.dkr.ecr.us-east-1.amazonaws.com/ticketing-platform:1.3.0 .
   docker push <cuenta>.dkr.ecr.us-east-1.amazonaws.com/ticketing-platform:1.3.0
   ```

4. **Etiqueta de costos** `env` activada en Billing, para que el presupuesto filtre por ambiente.
5. **Valores del ambiente.** Antes de aplicar, revisar en `terraform.tfvars` los correos de
   `alert_emails`, el origen de `CORS_ALLOWED_ORIGINS` y, si hay dominio propio, `certificate_arn`
   (ACM en us-east-1) y `aliases`.

### Comandos

```bash
cd deployment/terraform/envs/dev
terraform init -backend-config="bucket=<bucket-de-estado>"
terraform plan -out=dev.tfplan
terraform apply dev.tfplan
terraform output public_domain_name    # https://<dominio>/api/v1/events
```

En prod, el `plan` se revisa en el PR y el `apply` se ejecuta con aprobación. Los suscriptores de SNS
deben confirmar el correo para recibir las alarmas.

## Qué valida CI

| Workflow | Qué corre | Cuándo |
| --- | --- | --- |
| `build.yml` | `gradle build`: pruebas unitarias y de integración con Testcontainers, `validateStructure`, paridad de variables con `.env.example` y Terraform, cobertura ≥ 90 % | Cada PR y push a main |
| `terraform.yml` | `fmt`, `terraform test` por módulo y ambiente, `validate` por ambiente, tflint y checkov | PR y push a main que tocan `deployment/terraform` |
