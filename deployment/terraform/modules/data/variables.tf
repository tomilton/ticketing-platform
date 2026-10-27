variable "name" {
  description = "Prefix of the names of the key and its alias, for example ticketing-dev."
  type        = string
}

variable "kms_deletion_window_days" {
  description = "Days a deleted key waits before it is destroyed, from 7 to 30."
  type        = number

  validation {
    condition     = var.kms_deletion_window_days >= 7 && var.kms_deletion_window_days <= 30
    error_message = "The deletion window of a KMS key must be between 7 and 30 days."
  }
}
