variable "name" {
  description = "Prefix of every resource name, for example ticketing-dev."
  type        = string
}

variable "environment" {
  description = "Value of the env tag of the environment, the cost allocation tag the budget filters by."
  type        = string
}

variable "region" {
  description = "AWS region of the metrics shown in the dashboard."
  type        = string
}

variable "queue_name" {
  description = "Name of the queue of accepted orders."
  type        = string
}

variable "dead_letter_queue_name" {
  description = "Name of the dead letter queue."
  type        = string
}

variable "alb_arn_suffix" {
  description = "Suffix of the ARN of the internal load balancer, the dimension of its metrics."
  type        = string
}

variable "table_names" {
  description = "Names of the tables whose throttles raise an alarm, by logical name."
  type        = map(string)
}

variable "cluster_name" {
  description = "Name of the ECS cluster."
  type        = string
}

variable "service_names" {
  description = "Names of the ECS services of the API and the worker."
  type        = list(string)
}

variable "thresholds" {
  description = "Alarm thresholds: age of the oldest message in seconds and percentage of 5xx answers."
  type = object({
    oldest_message_age_seconds = number
    error_rate_percent         = number
  })
}

variable "alert_emails" {
  description = "Addresses subscribed to the alarms and the budget alerts."
  type        = list(string)
}

variable "monthly_budget_usd" {
  description = "Monthly cost budget of the environment in US dollars."
  type        = number
}

variable "kms_deletion_window_days" {
  description = "Days a deleted key waits before it is destroyed, from 7 to 30."
  type        = number
}
