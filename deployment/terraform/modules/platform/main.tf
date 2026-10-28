# The whole platform of one environment. Every environment uses this composition with its own values,
# so dev, qa and prod differ in size, never in structure. The names of the tables, indexes and queues
# are those of deployment/localstack/init/ready.d/, prefixed with the environment.

locals {
  name                    = "${var.app}-${var.environment}"
  container_port          = 8080
  health_check_path       = "/actuator/health"
  base_path               = "/api/v1"
  stream_path_pattern     = "/api/v1/events/*/availability/stream"
  alb_idle_timeout        = 4000
  stream_read_timeout     = 60
  visibility_timeout      = 60
  max_receive_count       = 5
  flow_log_retention_days = 30
  ecr_registry            = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com"
  image                   = "${local.ecr_registry}/${var.ecr_repository_name}:${var.image_tag}"
  ecr_repository_arn      = "arn:aws:ecr:${var.region}:${data.aws_caller_identity.current.account_id}:repository/${var.ecr_repository_name}"

  table_names = {
    events                 = "${local.name}-events"
    inventory              = "${local.name}-inventory"
    orders                 = "${local.name}-orders"
    order_audit            = "${local.name}-order_audit"
    idempotency            = "${local.name}-idempotency"
    customer_limits        = "${local.name}-customer_event_limits"
    scheduler_locks        = "${local.name}-scheduler_locks"
    payment_webhook_events = "${local.name}-payment_webhook_events"
  }
  index_names = {
    events_by_status_date = "by-status-date"
    expiring_reservations = "expiring-reservations"
    pending_publication   = "pending-publication"
  }
}

data "aws_caller_identity" "current" {}

module "network" {
  source = "../network"

  name                    = local.name
  region                  = var.region
  cidr_block              = var.vpc_cidr_block
  availability_zones      = var.availability_zones
  flow_log_retention_days = local.flow_log_retention_days
}

module "data" {
  source = "../data"

  name                     = local.name
  table_names              = local.table_names
  index_names              = local.index_names
  deletion_protection      = var.deletion_protection
  kms_deletion_window_days = var.kms_deletion_window_days
}

module "messaging" {
  source = "../messaging"

  name                       = local.name
  queue_name                 = "${local.name}-order-placed"
  dead_letter_queue_name     = "${local.name}-order-placed-dlq"
  visibility_timeout_seconds = local.visibility_timeout
  max_receive_count          = local.max_receive_count
  producer_role_arns         = [module.api.task_role_arn, module.worker.task_role_arn]
  consumer_role_arns         = [module.worker.task_role_arn]
  kms_deletion_window_days   = var.kms_deletion_window_days
}

module "cluster" {
  source = "../ecs-cluster"

  name = local.name
}

module "alb" {
  source = "../alb"

  name                 = local.name
  vpc_id               = module.network.vpc_id
  vpc_cidr_block       = module.network.vpc_cidr_block
  subnet_ids           = module.network.private_subnet_ids
  container_port       = local.container_port
  health_check_path    = local.health_check_path
  idle_timeout_seconds = local.alb_idle_timeout
  deletion_protection  = var.deletion_protection
}
