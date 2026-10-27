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

variable "ecr_repository_arn" {
  description = "ARN of the ECR repository the execution role pulls from."
  type        = string
}

variable "container_port" {
  description = "Port of the application, the value of APP_PORT."
  type        = number
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
