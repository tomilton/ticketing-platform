mock_provider "aws" {
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/mock"
    }
  }

  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:us-east-1:123456789012:log-group:mock"
    }
  }

  mock_resource "aws_ecs_task_definition" {
    defaults = {
      arn = "arn:aws:ecs:us-east-1:123456789012:task-definition/mock:1"
    }
  }

  mock_resource "aws_appautoscaling_policy" {
    defaults = {
      arn = "arn:aws:autoscaling:us-east-1:123456789012:scalingPolicy:mock"
    }
  }
}

run "runs_the_worker_on_spot_above_an_on_demand_base" {
  command = apply

  variables {
    role          = "worker"
    load_balancer = null
  }

  assert {
    condition     = contains(jsondecode(aws_ecs_task_definition.this.container_definitions)[0].environment, { name = "APP_ROLE", value = "worker" })
    error_message = "The worker task must run with APP_ROLE=worker."
  }

  assert {
    condition = toset([for strategy in aws_ecs_service.this.capacity_provider_strategy : "${strategy.capacity_provider}:${strategy.base}:${strategy.weight}"]) == toset([
      "FARGATE:1:1", "FARGATE_SPOT:0:3"
    ])
    error_message = "The worker keeps one On-Demand task and puts the rest on Spot."
  }

  assert {
    condition     = length(aws_ecs_service.this.load_balancer) == 0 && length(aws_vpc_security_group_ingress_rule.from_load_balancer) == 0
    error_message = "The worker receives no traffic."
  }

  assert {
    condition     = aws_cloudwatch_metric_alarm.backlog_high[0].metric_name == "ApproximateNumberOfMessagesVisible" && length(aws_appautoscaling_policy.cpu) == 0
    error_message = "The worker scales by the visible messages of the queue."
  }

  assert {
    condition     = contains(flatten([for statement in jsondecode(aws_iam_role_policy.task.policy).Statement : statement.Resource]), "arn:aws:sqs:us-east-1:123456789012:order-placed-dlq")
    error_message = "The worker moves failed messages to the dead letter queue."
  }

  assert {
    condition     = !contains(flatten([for statement in jsondecode(aws_iam_role_policy.task.policy).Statement : statement.Resource]), "arn:aws:dynamodb:us-east-1:123456789012:table/idempotency")
    error_message = "The worker does not use the idempotency keys."
  }
}

run "rejects_settings_that_miss_a_variable" {
  command = plan

  variables {
    settings = { CORS_ALLOWED_ORIGINS = "https://tickets.example.com" }
  }

  expect_failures = [var.settings]
}
