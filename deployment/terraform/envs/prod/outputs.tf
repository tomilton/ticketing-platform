output "public_domain_name" {
  description = "Public domain of the platform."
  value       = module.platform.public_domain_name
}

output "alert_topic_arn" {
  description = "ARN of the SNS topic of the alarms and the budget alerts."
  value       = module.platform.alert_topic_arn
}

output "service_names" {
  description = "Names of the ECS services of the API and the worker."
  value       = module.platform.service_names
}
