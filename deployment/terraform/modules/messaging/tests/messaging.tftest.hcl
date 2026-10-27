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

  mock_resource "aws_sqs_queue" {
    defaults = {
      arn = "arn:aws:sqs:us-east-1:123456789012:mock"
    }
  }
}

variables {
  name                       = "ticketing-test"
  queue_name                 = "order-placed"
  dead_letter_queue_name     = "order-placed-dlq"
  visibility_timeout_seconds = 60
  max_receive_count          = 5
  producer_role_arns         = ["arn:aws:iam::123456789012:role/api"]
  consumer_role_arns         = ["arn:aws:iam::123456789012:role/worker"]
  kms_deletion_window_days   = 7
}

run "moves_a_message_to_the_dead_letter_queue_after_five_deliveries" {
  command = apply

  assert {
    condition     = jsondecode(aws_sqs_queue.order_placed.redrive_policy).maxReceiveCount == 5
    error_message = "A message must reach the dead letter queue after five deliveries."
  }

  assert {
    condition     = jsondecode(aws_sqs_queue.order_placed.redrive_policy).deadLetterTargetArn == aws_sqs_queue.dead_letter.arn
    error_message = "The redrive policy must target the dead letter queue."
  }
}

run "keeps_the_retentions_visibility_and_long_polling" {
  command = plan

  assert {
    condition     = aws_sqs_queue.order_placed.message_retention_seconds == 345600 && aws_sqs_queue.dead_letter.message_retention_seconds == 1209600
    error_message = "The queue keeps messages 4 days and the dead letter queue 14 days."
  }

  assert {
    condition     = aws_sqs_queue.order_placed.receive_wait_time_seconds == 20
    error_message = "The queue must use long polling of 20 seconds."
  }

  assert {
    condition     = aws_sqs_queue.order_placed.visibility_timeout_seconds == 60
    error_message = "The visibility timeout must be the configured one."
  }
}

run "encrypts_both_queues_with_a_rotated_key" {
  command = apply

  assert {
    condition     = aws_sqs_queue.order_placed.kms_master_key_id == aws_kms_key.queues.arn && aws_sqs_queue.dead_letter.kms_master_key_id == aws_kms_key.queues.arn
    error_message = "Both queues must be encrypted with the customer managed key."
  }

  assert {
    condition     = aws_kms_key.queues.enable_key_rotation
    error_message = "The key must rotate."
  }
}

run "opens_the_queues_only_to_the_task_roles" {
  command = apply

  assert {
    condition = [for statement in jsondecode(aws_sqs_queue_policy.order_placed.policy).Statement : statement.Principal if statement.Effect == "Allow"] == [
      { AWS = ["arn:aws:iam::123456789012:role/api"] }, { AWS = ["arn:aws:iam::123456789012:role/worker"] }
    ]
    error_message = "Only the API publishes and only the worker consumes."
  }

  assert {
    condition     = !contains(flatten([for statement in jsondecode(aws_sqs_queue_policy.order_placed.policy).Statement : statement.Action if statement.Effect == "Allow"]), "sqs:*")
    error_message = "The roles get only the actions they use."
  }

  assert {
    condition     = [for statement in jsondecode(aws_sqs_queue_policy.dead_letter.policy).Statement : statement.Principal if statement.Effect == "Allow"] == [{ AWS = ["arn:aws:iam::123456789012:role/worker"] }]
    error_message = "Only the worker moves messages to the dead letter queue."
  }
}
