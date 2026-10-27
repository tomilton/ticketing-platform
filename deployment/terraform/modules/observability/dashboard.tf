# Operations dashboard: the queue, the API answers, the tasks and the throttles in one place.

locals {
  widget_width  = 12
  widget_height = 6
  widgets = [
    {
      title   = "Order queue"
      metrics = [["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.queue_name], ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", var.queue_name], ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.dead_letter_queue_name]]
    },
    {
      title   = "API answers"
      metrics = [["AWS/ApplicationELB", "RequestCount", "LoadBalancer", var.alb_arn_suffix], ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", var.alb_arn_suffix], ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix]]
    },
    {
      title   = "Tasks CPU"
      metrics = [for service in var.service_names : ["AWS/ECS", "CPUUtilization", "ClusterName", var.cluster_name, "ServiceName", service]]
    },
    {
      title   = "DynamoDB throttles"
      metrics = [for table in values(var.table_names) : ["AWS/DynamoDB", "WriteThrottleEvents", "TableName", table]]
    },
  ]
}

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = var.name
  dashboard_body = jsonencode({
    widgets = [for index, widget in local.widgets : {
      type   = "metric"
      x      = (index % 2) * local.widget_width
      y      = floor(index / 2) * local.widget_height
      width  = local.widget_width
      height = local.widget_height
      properties = {
        title   = widget.title
        region  = var.region
        metrics = widget.metrics
        stat    = "Sum"
        period  = local.period_seconds
      }
    }]
  })
}
