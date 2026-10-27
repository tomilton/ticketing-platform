output "table_arns" {
  description = "ARN of each table, by its logical name."
  value       = { for key, table in aws_dynamodb_table.this : key => table.arn }
}

output "kms_key_arn" {
  description = "ARN of the key that encrypts the tables."
  value       = aws_kms_key.tables.arn
}
