# Public entry and observability of the environment.

module "edge" {
  source = "../edge"

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  name                        = local.name
  subnet_ids                  = module.network.private_subnet_ids
  vpc_link_security_group_id  = module.alb.vpc_link_security_group_id
  alb_arn                     = module.alb.alb_arn
  alb_dns_name                = module.alb.alb_dns_name
  alb_listener_arn            = module.alb.listener_arn
  base_path                   = local.base_path
  stream_path_pattern         = local.stream_path_pattern
  stream_read_timeout_seconds = local.stream_read_timeout
  throttling                  = var.edge.throttling
  jwt                         = var.edge.jwt
  waf_rate_limit_per_ip       = var.edge.waf_rate_limit_per_ip
  certificate_arn             = var.edge.certificate_arn
  aliases                     = var.edge.aliases
  price_class                 = var.edge.price_class
  log_retention_days          = var.observability.log_retention_days
}

module "observability" {
  source = "../observability"

  name                     = local.name
  environment              = var.environment
  region                   = var.region
  queue_name               = module.messaging.queue_name
  dead_letter_queue_name   = module.messaging.dead_letter_queue_name
  alb_arn_suffix           = module.alb.alb_arn_suffix
  table_names              = local.table_names
  cluster_name             = module.cluster.cluster_name
  service_names            = [module.api.service_name, module.worker.service_name]
  alert_emails             = var.observability.alert_emails
  monthly_budget_usd       = var.observability.monthly_budget_usd
  kms_deletion_window_days = var.kms_deletion_window_days
  thresholds = {
    oldest_message_age_seconds = var.observability.oldest_message_age_seconds
    error_rate_percent         = var.observability.error_rate_percent
  }
}
