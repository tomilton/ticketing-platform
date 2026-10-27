# Alarms of what breaks the promise of the platform: messages in the dead letter queue, orders waiting
# too long, 5xx answers and DynamoDB throttles. Each one notifies the alert topic.

locals {
  period_seconds     = 60
  error_rate_periods = 5
}

resource "aws_cloudwatch_metric_alarm" "dead_letter_messages" {
  alarm_name          = "${var.name}-dead-letter-messages"
  alarm_description   = "Orders whose processing failed for good; each one needs a review"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = var.dead_letter_queue_name }
  statistic           = "Maximum"
  period              = local.period_seconds
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "oldest_message_age" {
  alarm_name          = "${var.name}-oldest-message-age"
  alarm_description   = "Accepted orders wait too long for the worker"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = var.queue_name }
  statistic           = "Maximum"
  period              = local.period_seconds
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.thresholds.oldest_message_age_seconds
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "api_error_rate" {
  alarm_name          = "${var.name}-api-5xx-rate"
  alarm_description   = "Percentage of 5xx answers of the API"
  evaluation_periods  = local.error_rate_periods
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.thresholds.error_rate_percent
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  metric_query {
    id          = "error_rate"
    expression  = "100 * (FILL(target_errors, 0) + FILL(balancer_errors, 0)) / requests"
    label       = "5xx percentage"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = {
      target_errors   = "HTTPCode_Target_5XX_Count"
      balancer_errors = "HTTPCode_ELB_5XX_Count"
      requests        = "RequestCount"
    }

    content {
      id = metric_query.key

      metric {
        namespace   = "AWS/ApplicationELB"
        metric_name = metric_query.value
        dimensions  = { LoadBalancer = var.alb_arn_suffix }
        period      = local.period_seconds
        stat        = "Sum"
      }
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "table_throttles" {
  for_each = var.table_names

  alarm_name          = "${var.name}-${each.key}-throttles"
  alarm_description   = "DynamoDB throttled requests on ${each.value}"
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  metric_query {
    id          = "throttles"
    expression  = "FILL(reads, 0) + FILL(writes, 0)"
    label       = "Throttle events"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = { reads = "ReadThrottleEvents", writes = "WriteThrottleEvents" }

    content {
      id = metric_query.key

      metric {
        namespace   = "AWS/DynamoDB"
        metric_name = metric_query.value
        dimensions  = { TableName = each.value }
        period      = local.period_seconds
        stat        = "Sum"
      }
    }
  }
}
