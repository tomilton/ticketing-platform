output "alert_topic_arn" {
  description = "ARN of the SNS topic of the alarms and the budget alerts."
  value       = aws_sns_topic.alerts.arn
}

output "dashboard_name" {
  description = "Name of the operations dashboard."
  value       = aws_cloudwatch_dashboard.this.dashboard_name
}
