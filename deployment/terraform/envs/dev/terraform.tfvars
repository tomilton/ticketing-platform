app                      = "ticketing"
environment              = "dev"
owner                    = "ticketing-team"
cost_center              = "ticketing-dev"
region                   = "us-east-1"
availability_zones       = ["us-east-1a", "us-east-1b", "us-east-1c"]
vpc_cidr_block           = "10.10.0.0/16"
ecr_repository_name      = "ticketing-platform"
image_tag                = "1.3.0"
deletion_protection      = false
kms_deletion_window_days = 7

services = {
  api    = { cpu = 512, memory = 1024, min_capacity = 1, max_capacity = 2 }
  worker = { cpu = 512, memory = 1024, min_capacity = 1, max_capacity = 2 }
}

scaling = {
  cpu_percent           = 60
  requests_per_target   = 500
  messages_per_scale_up = 100
}

edge = {
  throttling            = { rate = 100, burst = 200, orders_rate = 20, orders_burst = 40 }
  waf_rate_limit_per_ip = 1000
  jwt                   = { enabled = false, issuer = "", audience = [] }
  certificate_arn       = null
  aliases               = []
  price_class           = "PriceClass_100"
}

observability = {
  log_retention_days         = 14
  oldest_message_age_seconds = 60
  error_rate_percent         = 1
  alert_emails               = ["ticketing-team@example.com"]
  monthly_budget_usd         = 150
}

settings = {
  CORS_ALLOWED_ORIGINS                  = "https://tickets-dev.example.com"
  MANAGEMENT_HEALTH_SHOW_COMPONENTS     = "always"
  DYNAMODB_API_CALL_TIMEOUT             = "PT2S"
  DYNAMODB_API_CALL_ATTEMPT_TIMEOUT     = "PT0.5S"
  DYNAMODB_BATCH_CONCURRENCY            = "4"
  DYNAMODB_HEALTH_CHECK_TIMEOUT         = "PT1S"
  DYNAMODB_TRANSACTION_CONFLICT_RETRIES = "10"
  DYNAMODB_RESERVE_TIMEOUT              = "PT5S"
  SQS_SEND_TIMEOUT                      = "PT1S"
  SQS_WAIT_TIME                         = "PT20S"
  SQS_MAX_MESSAGES                      = "10"
  SQS_MAX_CONCURRENCY                   = "8"
  PAYMENT_SIMULATOR_MODE                = "APPROVE"
  PAYMENT_SIMULATOR_LATENCY             = "PT0.2S"
  PAYMENT_TIMEOUT                       = "PT2S"
  PAYMENT_SIMULATOR_APPROVAL_RATE       = "0.5"
  PAYMENT_SIMULATOR_SLOW_LATENCY        = "PT5S"
  INVENTORY_SHARD_COUNT                 = "1"
  AVAILABILITY_POLL_INTERVAL            = "PT1S"
  AVAILABILITY_BATCH_MAX_IDS            = "100"
  AVAILABILITY_STREAM_HEARTBEAT         = "PT15S"
  AVAILABILITY_STREAM_MAX_DURATION      = "PT30M"
  AVAILABILITY_STREAM_RETRY             = "PT1S"
  ORDERS_MAX_TICKETS_PER_ORDER          = "6"
  ORDERS_MAX_TICKETS_PER_CUSTOMER       = "6"
  ORDERS_RESERVATION_TTL                = "PT10M"
  ORDERS_REPUBLISH_INTERVAL             = "PT10S"
  ORDERS_REPUBLISH_GRACE                = "PT5S"
  ORDERS_REPUBLISH_CONCURRENCY          = "4"
  SWEEP_INTERVAL                        = "PT30S"
  SWEEP_LOCK_LEASE                      = "PT60S"
  SWEEP_BATCH_SIZE                      = "500"
  SWEEP_CONCURRENCY                     = "8"
  COMPLIMENTARY_MAX_PER_GRANT           = "50"
  IDEMPOTENCY_TTL                       = "PT24H"
  IDEMPOTENCY_LEASE                     = "PT10S"
  EVENTS_PAGE_DEFAULT_SIZE              = "20"
  EVENTS_PAGE_MAX_SIZE                  = "100"
}
