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

run "runs_the_api_behind_the_load_balancer_with_its_role" {
  command = apply

  assert {
    condition     = contains(jsondecode(aws_ecs_task_definition.this.container_definitions)[0].environment, { name = "APP_ROLE", value = "api" })
    error_message = "The API task must run with APP_ROLE=api."
  }

  assert {
    condition     = length(jsondecode(aws_ecs_task_definition.this.container_definitions)[0].environment) == 88
    error_message = "The task must receive the 88 variables of application.yaml that exist in AWS."
  }

  assert {
    condition     = length(aws_ecs_service.this.load_balancer) == 1 && length(aws_vpc_security_group_ingress_rule.from_load_balancer) == 1
    error_message = "The API registers in the target group and accepts traffic only from the load balancer."
  }

  assert {
    condition     = one(aws_ecs_service.this.network_configuration).assign_public_ip == false
    error_message = "The tasks never get a public address."
  }

  assert {
    condition     = one(aws_ecs_service.this.deployment_circuit_breaker).rollback
    error_message = "A failed deployment must roll back."
  }
}

run "scales_the_api_by_cpu_and_requests" {
  command = apply

  assert {
    condition     = one(aws_appautoscaling_policy.cpu[0].target_tracking_scaling_policy_configuration).target_value == 60
    error_message = "The API scales at 60 % of CPU."
  }

  assert {
    condition     = one(one(aws_appautoscaling_policy.requests[0].target_tracking_scaling_policy_configuration).predefined_metric_specification).predefined_metric_type == "ALBRequestCountPerTarget"
    error_message = "The API also scales by requests per task."
  }

  assert {
    condition     = length(aws_appautoscaling_policy.backlog_out) == 0
    error_message = "The API does not scale by the queue."
  }
}

run "grants_the_api_only_its_tables_and_queue" {
  command = apply

  assert {
    condition     = !contains(flatten([for statement in jsondecode(aws_iam_role_policy.task.policy).Statement : statement.Resource]), "*")
    error_message = "The task role must not grant any action on every resource."
  }

  assert {
    condition     = !contains(flatten([for statement in jsondecode(aws_iam_role_policy.task.policy).Statement : statement.Resource]), "arn:aws:dynamodb:us-east-1:123456789012:table/scheduler_locks")
    error_message = "The API does not use the scheduler locks."
  }

  assert {
    condition     = !contains(flatten([for statement in jsondecode(aws_iam_role_policy.task.policy).Statement : statement.Action]), "sqs:ReceiveMessage")
    error_message = "The API only publishes to the queue."
  }

  assert {
    condition     = !contains([for statement in jsondecode(aws_iam_role_policy.task.policy).Statement : statement.Sid], "DeadLetterQueue")
    error_message = "The API never writes to the dead letter queue."
  }
}
