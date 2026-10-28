# Environment of the container: every ${VAR} of applications/app-service/src/main/resources/application.yaml,
# and nothing else. verifyEnvironmentParity in build.gradle checks both directions against this file.
# The names of the resources come from the other modules, the contract of the API is fixed, and the
# business and tuning settings come from each environment. AWS_ENDPOINT_URL only exists locally, and
# the credentials come from the task role.

locals {
  # Variables that reach the container from Secrets Manager through var.secrets, never as plain environment.
  secret_names = [
    "WEBHOOK_HMAC_SECRET",
  ]

  setting_names = [
    "CORS_ALLOWED_ORIGINS",
    "JWT_JWK_SET_URI",
    "JWT_ISSUER",
    "JWT_AUDIENCE",
    "JWT_ROLES_CLAIM",
    "JWT_ADMIN_ROLE",
    "JWT_CLOCK_SKEW",
    "MANAGEMENT_HEALTH_SHOW_COMPONENTS",
    "DYNAMODB_API_CALL_TIMEOUT",
    "DYNAMODB_API_CALL_ATTEMPT_TIMEOUT",
    "DYNAMODB_BATCH_CONCURRENCY",
    "DYNAMODB_HEALTH_CHECK_TIMEOUT",
    "DYNAMODB_TRANSACTION_CONFLICT_RETRIES",
    "DYNAMODB_RESERVE_TIMEOUT",
    "SQS_SEND_TIMEOUT",
    "SQS_WAIT_TIME",
    "SQS_MAX_MESSAGES",
    "SQS_MAX_CONCURRENCY",
    "PAYMENT_SIMULATOR_MODE",
    "PAYMENT_SIMULATOR_LATENCY",
    "PAYMENT_TIMEOUT",
    "PAYMENT_SIMULATOR_APPROVAL_RATE",
    "PAYMENT_SIMULATOR_SLOW_LATENCY",
    "INVENTORY_SHARD_COUNT",
    "AVAILABILITY_POLL_INTERVAL",
    "AVAILABILITY_BATCH_MAX_IDS",
    "AVAILABILITY_STREAM_HEARTBEAT",
    "AVAILABILITY_STREAM_MAX_DURATION",
    "AVAILABILITY_STREAM_RETRY",
    "ORDERS_MAX_TICKETS_PER_ORDER",
    "ORDERS_MAX_TICKETS_PER_CUSTOMER",
    "ORDERS_RESERVATION_TTL",
    "ORDERS_REPUBLISH_INTERVAL",
    "ORDERS_REPUBLISH_GRACE",
    "ORDERS_REPUBLISH_CONCURRENCY",
    "SWEEP_INTERVAL",
    "SWEEP_LOCK_LEASE",
    "SWEEP_BATCH_SIZE",
    "SWEEP_CONCURRENCY",
    "COMPLIMENTARY_MAX_PER_GRANT",
    "IDEMPOTENCY_TTL",
    "IDEMPOTENCY_LEASE",
    "EVENTS_PAGE_DEFAULT_SIZE",
    "EVENTS_PAGE_MAX_SIZE",
  ]

  fixed_environment = {
    APP_PORT                               = tostring(var.container_port)
    APP_NAME                               = var.name
    APP_ROLE                               = var.role
    LOG_STRUCTURED_FORMAT                  = "ecs"
    REACTOR_CONTEXT_PROPAGATION            = "auto"
    MANAGEMENT_ENDPOINTS_EXPOSURE          = "health,prometheus"
    MANAGEMENT_HEALTH_PROBES_ENABLED       = "true"
    JWT_MODE                               = "JWKS"
    RATE_LIMIT_ORDERS_PER_PERIOD           = "10"
    RATE_LIMIT_ORDERS_PERIOD               = "PT1M"
    HTTP_MAX_BODY_SIZE                     = "16KB"
    WEBHOOK_EVENT_TTL                      = "P7D"
    WEBHOOK_TOLERANCE                      = "PT5M"
    PAYMENT_CIRCUIT_FAILURE_RATE_THRESHOLD = "50"
    PAYMENT_CIRCUIT_SLIDING_WINDOW_SIZE    = "20"
    PAYMENT_CIRCUIT_MINIMUM_CALLS          = "10"
    PAYMENT_CIRCUIT_WAIT_IN_OPEN           = "PT30S"
    PAYMENT_CIRCUIT_HALF_OPEN_CALLS        = "3"
    ROUTES_BASE_PATH                       = "/api/v1"
    ROUTES_EVENTS                          = "/events"
    ROUTES_EVENT_BY_ID                     = "/events/{eventId}"
    ROUTES_EVENT_AVAILABILITY              = "/events/{eventId}/availability"
    ROUTES_BATCH_AVAILABILITY              = "/events/availability"
    ROUTES_AVAILABILITY_STREAM             = "/events/{eventId}/availability/stream"
    ROUTES_ORDERS                          = "/orders"
    ROUTES_ORDER_BY_ID                     = "/orders/{orderId}"
    ROUTES_PAYMENT_WEBHOOK                 = "/webhooks/payments"
    ROUTES_COMPLIMENTARY                   = "/events/{eventId}/complimentary"
    AWS_REGION                             = var.region
    DYNAMODB_EVENTS_TABLE                  = var.table_names["events"]
    DYNAMODB_INVENTORY_TABLE               = var.table_names["inventory"]
    DYNAMODB_ORDERS_TABLE                  = var.table_names["orders"]
    DYNAMODB_ORDER_AUDIT_TABLE             = var.table_names["order_audit"]
    DYNAMODB_IDEMPOTENCY_TABLE             = var.table_names["idempotency"]
    DYNAMODB_CUSTOMER_LIMITS_TABLE         = var.table_names["customer_limits"]
    DYNAMODB_SCHEDULER_LOCKS_TABLE         = var.table_names["scheduler_locks"]
    DYNAMODB_WEBHOOK_EVENTS_TABLE          = var.table_names["payment_webhook_events"]
    DYNAMODB_EVENTS_BY_STATUS_DATE_INDEX   = var.index_names.events_by_status_date
    DYNAMODB_EXPIRING_RESERVATIONS_INDEX   = var.index_names.expiring_reservations
    DYNAMODB_PENDING_PUBLICATION_INDEX     = var.index_names.pending_publication
    SQS_ORDER_PLACED_QUEUE                 = var.queue.name
    SQS_ORDER_PLACED_DLQ                   = var.queue.dead_letter_queue_name
    SQS_PAYMENT_CONFIRMED_QUEUE            = var.payment_queue.name
    SQS_PAYMENT_CONFIRMED_DLQ              = var.payment_queue.dead_letter_queue_name
  }

  environment = [
    for name, value in merge(local.fixed_environment, var.settings) : { name = name, value = value }
  ]
}
