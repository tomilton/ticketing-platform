variable "app" {
  description = "Name of the application, the prefix of every resource and the app tag."
  type        = string
}

variable "environment" {
  description = "Environment: dev, qa or prod."
  type        = string

  validation {
    condition     = contains(["dev", "qa", "prod"], var.environment)
    error_message = "The environment must be dev, qa or prod."
  }
}

variable "region" {
  description = "AWS region of the platform."
  type        = string
}

variable "availability_zones" {
  description = "The three availability zones of the region."
  type        = list(string)
}

variable "vpc_cidr_block" {
  description = "CIDR block of the VPC."
  type        = string
}

variable "ecr_repository_name" {
  description = "ECR repository of the image of the application, in the account and region of the platform."
  type        = string
}

variable "image_tag" {
  description = "Tag of the image to deploy, the version of the application, for example 1.3.0."
  type        = string
}

variable "services" {
  description = "Size and scaling range of the API and the worker."
  type = map(object({
    cpu          = number
    memory       = number
    min_capacity = number
    max_capacity = number
  }))

  validation {
    condition     = toset(keys(var.services)) == toset(["api", "worker"])
    error_message = "The services must be api and worker."
  }

  validation {
    condition     = var.environment != "prod" || alltrue([for service in values(var.services) : service.min_capacity >= 2])
    error_message = "Production runs at least two tasks of each service."
  }
}

variable "scaling" {
  description = "CPU target and requests per task of the API, and visible messages per step of the worker."
  type = object({
    cpu_percent           = number
    requests_per_target   = number
    messages_per_scale_up = number
  })
}

variable "deletion_protection" {
  description = "Protection against deletion of the tables and the load balancer."
  type        = bool

  validation {
    condition     = var.environment != "prod" || var.deletion_protection
    error_message = "Production protects its tables and load balancer against deletion."
  }
}

variable "settings" {
  description = "Business and tuning settings of the application; see modules/ecs-service/environment.tf."
  type        = map(string)
}

variable "edge" {
  description = "Throttling, WAF limit, JWT, certificate, aliases and price class of the public entry."
  type = object({
    throttling = object({
      rate         = number
      burst        = number
      orders_rate  = number
      orders_burst = number
    })
    waf_rate_limit_per_ip = number
    jwt = object({
      enabled  = bool
      issuer   = string
      audience = list(string)
    })
    certificate_arn = string
    aliases         = list(string)
    price_class     = string
  })
}

variable "observability" {
  description = "Log retention, alarm thresholds, alert addresses and monthly budget."
  type = object({
    log_retention_days         = number
    oldest_message_age_seconds = number
    error_rate_percent         = number
    alert_emails               = list(string)
    monthly_budget_usd         = number
  })
}

variable "kms_deletion_window_days" {
  description = "Days a deleted key waits before it is destroyed, from 7 to 30."
  type        = number
}
