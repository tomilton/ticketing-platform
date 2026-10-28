variable "name" {
  description = "Prefix of the names of the key and its alias, for example ticketing-dev."
  type        = string
}

variable "table_names" {
  description = "Names of the eight tables, the same the application reads from its DYNAMODB_*_TABLE variables."
  type = object({
    events                 = string
    inventory              = string
    orders                 = string
    order_audit            = string
    idempotency            = string
    customer_limits        = string
    scheduler_locks        = string
    payment_webhook_events = string
  })
}

variable "index_names" {
  description = "Names of the global secondary indexes, the same the application reads from its DYNAMODB_*_INDEX variables."
  type = object({
    events_by_status_date = string
    expiring_reservations = string
    pending_publication   = string
  })
}

variable "deletion_protection" {
  description = "Whether the tables are protected against deletion; true in production."
  type        = bool
}

variable "kms_deletion_window_days" {
  description = "Days a deleted key waits before it is destroyed, from 7 to 30."
  type        = number

  validation {
    condition     = var.kms_deletion_window_days >= 7 && var.kms_deletion_window_days <= 30
    error_message = "The deletion window of a KMS key must be between 7 and 30 days."
  }
}
