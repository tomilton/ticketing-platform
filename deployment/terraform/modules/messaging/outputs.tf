output "queue_arn" {
  description = "ARN of the queue of accepted orders."
  value       = aws_sqs_queue.order_placed.arn
}

output "queue_name" {
  description = "Name of the queue of accepted orders."
  value       = aws_sqs_queue.order_placed.name
}

output "dead_letter_queue_arn" {
  description = "ARN of the dead letter queue."
  value       = aws_sqs_queue.dead_letter.arn
}

output "dead_letter_queue_name" {
  description = "Name of the dead letter queue."
  value       = aws_sqs_queue.dead_letter.name
}

output "kms_key_arn" {
  description = "ARN of the key that encrypts the queues."
  value       = aws_kms_key.queues.arn
}
