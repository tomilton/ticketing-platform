output "kms_key_arn" {
  description = "ARN of the key that encrypts the tables."
  value       = aws_kms_key.tables.arn
}
