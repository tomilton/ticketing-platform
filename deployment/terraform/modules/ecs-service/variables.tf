variable "name" {
  description = "Prefix of every resource name, for example ticketing-dev."
  type        = string
}

variable "role" {
  description = "Role of the service, the value of APP_ROLE: api or worker."
  type        = string

  validation {
    condition     = contains(["api", "worker"], var.role)
    error_message = "The role must be api or worker."
  }
}

variable "region" {
  description = "AWS region of the tables, the queues and the logs."
  type        = string
}

variable "cluster_arn" {
  description = "ARN of the ECS cluster."
  type        = string
}

variable "image" {
  description = "Image of the application in ECR, with its tag or digest; the same for the API and the worker."
  type        = string
}

variable "ecr_repository_arn" {
  description = "ARN of the ECR repository the execution role pulls from."
  type        = string
}

variable "cpu" {
  description = "CPU units of a task, for example 512."
  type        = number
}

variable "memory" {
  description = "Memory of a task in MiB, for example 1024."
  type        = number
}

variable "container_port" {
  description = "Port of the application, the value of APP_PORT."
  type        = number
}

variable "health_check_path" {
  description = "Path of the health check of the application."
  type        = string
}

variable "min_capacity" {
  description = "Minimum number of tasks; at least 2 in production."
  type        = number
}

variable "vpc_id" {
  description = "VPC of the tasks."
  type        = string
}

variable "vpc_cidr_block" {
  description = "CIDR block of the VPC, where the interface endpoints answer."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets of the tasks."
  type        = list(string)
}

variable "gateway_prefix_list_ids" {
  description = "Prefix lists of the DynamoDB and S3 gateway endpoints."
  type        = list(string)
}

variable "load_balancer" {
  description = "Load balancer of the API; null for the worker, which receives no traffic."
  type = object({
    security_group_id       = string
    target_group_arn        = string
    alb_arn_suffix          = string
    target_group_arn_suffix = string
  })
}

variable "log_retention_days" {
  description = "Days the application logs are kept."
  type        = number
}

variable "table_names" {
  description = "Names of the seven tables, by logical name."
  type        = map(string)
}

variable "table_arns" {
  description = "ARNs of the seven tables, by logical name."
  type        = map(string)
}

variable "index_names" {
  description = "Names of the global secondary indexes."
  type = object({
    events_by_status_date = string
    expiring_reservations = string
    pending_publication   = string
  })
}

variable "queue" {
  description = "Queue of accepted orders and its dead letter queue."
  type = object({
    name                   = string
    arn                    = string
    dead_letter_queue_name = string
    dead_letter_queue_arn  = string
  })
}

variable "kms_key_arns" {
  description = "Keys of the tables and the queues the tasks use."
  type        = list(string)
}

variable "settings" {
  description = "Business and tuning settings of the application, by environment variable name; see environment.tf."
  type        = map(string)

  validation {
    condition     = toset(keys(var.settings)) == toset(local.setting_names)
    error_message = "The settings must declare exactly the variables listed in local.setting_names."
  }
}
