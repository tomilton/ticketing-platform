mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }

  mock_resource "aws_kms_key" {
    defaults = {
      arn = "arn:aws:kms:us-east-1:123456789012:key/mock"
    }
  }

  mock_resource "aws_sns_topic" {
    defaults = {
      arn = "arn:aws:sns:us-east-1:123456789012:ticketing-test-alerts"
    }
  }
}

variables {
  name                   = "ticketing-test"
  environment            = "test"
  region                 = "us-east-1"
  queue_name             = "order-placed"
  dead_letter_queue_name = "order-placed-dlq"
  alb_arn_suffix         = "app/ticketing-test-api/1"
  table_names = {
    events    = "events"
    inventory = "inventory"
    orders    = "orders"
  }
  cluster_name  = "ticketing-test"
  service_names = ["ticketing-test-api", "ticketing-test-worker"]
  thresholds = {
    oldest_message_age_seconds = 60
    error_rate_percent         = 1
  }
  alert_emails             = ["oncall@example.com"]
  monthly_budget_usd       = 300
  kms_deletion_window_days = 7
}

run "alarms_on_messages_in_the_dead_letter_queue" {
  command = apply

  assert {
    condition     = aws_cloudwatch_metric_alarm.dead_letter_messages.dimensions.QueueName == "order-placed-dlq" && aws_cloudwatch_metric_alarm.dead_letter_messages.threshold == 0
    error_message = "Any message in the dead letter queue must raise the alarm."
  }

  assert {
    condition     = aws_cloudwatch_metric_alarm.dead_letter_messages.comparison_operator == "GreaterThanThreshold"
    error_message = "The alarm fires from the first message."
  }
}

run "alarms_on_orders_waiting_more_than_a_minute" {
  command = apply

  assert {
    condition     = aws_cloudwatch_metric_alarm.oldest_message_age.metric_name == "ApproximateAgeOfOldestMessage" && aws_cloudwatch_metric_alarm.oldest_message_age.threshold == 60
    error_message = "The age of the oldest message must alarm above 60 seconds."
  }
}

run "alarms_on_more_than_one_percent_of_5xx" {
  command = apply

  assert {
    condition     = aws_cloudwatch_metric_alarm.api_error_rate.threshold == 1
    error_message = "The 5xx rate must alarm above 1 %."
  }

  assert {
    condition     = strcontains(one([for query in aws_cloudwatch_metric_alarm.api_error_rate.metric_query : query.expression if query.return_data == true]), "/ requests")
    error_message = "The alarm must divide the 5xx answers by the requests."
  }
}

run "alarms_on_throttles_of_every_table" {
  command = apply

  assert {
    condition     = toset(keys(aws_cloudwatch_metric_alarm.table_throttles)) == toset(["events", "inventory", "orders"])
    error_message = "Every table must have its throttle alarm."
  }
}

run "notifies_an_encrypted_topic_and_budgets_the_environment" {
  command = apply

  assert {
    condition = alltrue([for alarm in concat(
      [aws_cloudwatch_metric_alarm.dead_letter_messages, aws_cloudwatch_metric_alarm.oldest_message_age, aws_cloudwatch_metric_alarm.api_error_rate],
      values(aws_cloudwatch_metric_alarm.table_throttles)
    ) : contains(alarm.alarm_actions, aws_sns_topic.alerts.arn)])
    error_message = "Every alarm must notify the alert topic."
  }

  assert {
    condition     = aws_sns_topic.alerts.kms_master_key_id == aws_kms_key.alerts.arn
    error_message = "The alert topic must be encrypted with its own key."
  }

  assert {
    condition     = aws_budgets_budget.monthly.limit_amount == "300" && length(aws_budgets_budget.monthly.notification) == 2
    error_message = "The environment must have a monthly budget with actual and forecast alerts."
  }

  assert {
    condition     = one(aws_budgets_budget.monthly.cost_filter).values == tolist(["user:env$test"])
    error_message = "The budget counts only the resources of the environment."
  }
}
