variable "name" {
  description = "Prefix of the names of the key and its alias, for example ticketing-dev."
  type        = string
}

variable "queue_name" {
  description = "Name of the queue of accepted orders, the same the application reads from SQS_ORDER_PLACED_QUEUE."
  type        = string
}

variable "dead_letter_queue_name" {
  description = "Name of the dead letter queue, the same the application reads from SQS_ORDER_PLACED_DLQ."
  type        = string
}

variable "visibility_timeout_seconds" {
  description = "Seconds a received message stays hidden before SQS delivers it again."
  type        = number
}

variable "max_receive_count" {
  description = "Deliveries of a message before SQS moves it to the dead letter queue."
  type        = number
}

variable "producer_role_arns" {
  description = "Roles that publish accepted orders: the task role of the API."
  type        = list(string)
}

variable "consumer_role_arns" {
  description = "Roles that consume the orders and move failed messages to the dead letter queue: the task role of the worker."
  type        = list(string)
}

variable "kms_deletion_window_days" {
  description = "Days a deleted key waits before it is destroyed, from 7 to 30."
  type        = number
}
