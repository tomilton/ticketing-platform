output "alert_topic_arn" {
  description = "ARN of the SNS topic of the alarms and the budget alerts."
  value       = aws_sns_topic.alerts.arn
}
