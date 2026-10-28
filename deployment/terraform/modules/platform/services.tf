# The API and the worker: the same image and module, with a different APP_ROLE, size and scaling.

locals {
  service_inputs = {
    region                  = var.region
    cluster_arn             = module.cluster.cluster_arn
    cluster_name            = module.cluster.cluster_name
    vpc_id                  = module.network.vpc_id
    vpc_cidr_block          = module.network.vpc_cidr_block
    subnet_ids              = module.network.private_subnet_ids
    gateway_prefix_list_ids = module.network.gateway_prefix_list_ids
    table_arns              = module.data.table_arns
    kms_key_arns            = [module.data.kms_key_arn, module.messaging.kms_key_arn, module.payment_messaging.kms_key_arn]
    queue = {
      name                   = module.messaging.queue_name
      arn                    = module.messaging.queue_arn
      dead_letter_queue_name = module.messaging.dead_letter_queue_name
      dead_letter_queue_arn  = module.messaging.dead_letter_queue_arn
    }
    payment_queue = {
      name                   = module.payment_messaging.queue_name
      arn                    = module.payment_messaging.queue_arn
      dead_letter_queue_name = module.payment_messaging.dead_letter_queue_name
      dead_letter_queue_arn  = module.payment_messaging.dead_letter_queue_arn
    }
  }
}

module "api" {
  source = "../ecs-service"

  name                    = local.name
  role                    = "api"
  image                   = local.image
  ecr_repository_arn      = local.ecr_repository_arn
  cpu                     = var.services["api"].cpu
  memory                  = var.services["api"].memory
  min_capacity            = var.services["api"].min_capacity
  max_capacity            = var.services["api"].max_capacity
  container_port          = local.container_port
  health_check_path       = local.health_check_path
  log_retention_days      = var.observability.log_retention_days
  table_names             = local.table_names
  index_names             = local.index_names
  settings                = var.settings
  scaling                 = var.scaling
  region                  = local.service_inputs.region
  cluster_arn             = local.service_inputs.cluster_arn
  cluster_name            = local.service_inputs.cluster_name
  vpc_id                  = local.service_inputs.vpc_id
  vpc_cidr_block          = local.service_inputs.vpc_cidr_block
  subnet_ids              = local.service_inputs.subnet_ids
  gateway_prefix_list_ids = local.service_inputs.gateway_prefix_list_ids
  table_arns              = local.service_inputs.table_arns
  kms_key_arns            = local.service_inputs.kms_key_arns
  queue                   = local.service_inputs.queue
  payment_queue           = local.service_inputs.payment_queue
  load_balancer = {
    security_group_id       = module.alb.alb_security_group_id
    target_group_arn        = module.alb.target_group_arn
    alb_arn_suffix          = module.alb.alb_arn_suffix
    target_group_arn_suffix = module.alb.target_group_arn_suffix
  }
}

module "worker" {
  source = "../ecs-service"

  name                    = local.name
  role                    = "worker"
  image                   = local.image
  ecr_repository_arn      = local.ecr_repository_arn
  cpu                     = var.services["worker"].cpu
  memory                  = var.services["worker"].memory
  min_capacity            = var.services["worker"].min_capacity
  max_capacity            = var.services["worker"].max_capacity
  container_port          = local.container_port
  health_check_path       = local.health_check_path
  log_retention_days      = var.observability.log_retention_days
  table_names             = local.table_names
  index_names             = local.index_names
  settings                = var.settings
  scaling                 = var.scaling
  region                  = local.service_inputs.region
  cluster_arn             = local.service_inputs.cluster_arn
  cluster_name            = local.service_inputs.cluster_name
  vpc_id                  = local.service_inputs.vpc_id
  vpc_cidr_block          = local.service_inputs.vpc_cidr_block
  subnet_ids              = local.service_inputs.subnet_ids
  gateway_prefix_list_ids = local.service_inputs.gateway_prefix_list_ids
  table_arns              = local.service_inputs.table_arns
  kms_key_arns            = local.service_inputs.kms_key_arns
  queue                   = local.service_inputs.queue
  payment_queue           = local.service_inputs.payment_queue
  load_balancer           = null
}
