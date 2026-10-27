# The queue of accepted orders and its dead letter queue, as deployment/localstack/init/ready.d/02-sqs.sh
# creates them locally, encrypted with a customer managed key and open only to the task roles.

locals {
  message_wait_seconds      = 20
  queue_retention_seconds   = 345600
  dead_letter_retention     = 1209600
  producer_actions          = ["sqs:SendMessage", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
  consumer_actions          = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
  dead_letter_actions       = ["sqs:SendMessage", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
  secure_transport_required = { Bool = { "aws:SecureTransport" = "false" } }
}

data "aws_caller_identity" "current" {}

resource "aws_kms_key" "queues" {
  description             = "Encryption of the SQS queues of ${var.name}"
  enable_key_rotation     = true
  deletion_window_in_days = var.kms_deletion_window_days
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AccountAdministration"
      Effect    = "Allow"
      Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
      Action    = "kms:*"
      Resource  = "*"
    }]
  })
}

resource "aws_kms_alias" "queues" {
  name          = "alias/${var.name}-sqs"
  target_key_id = aws_kms_key.queues.key_id
}

resource "aws_sqs_queue" "dead_letter" {
  name                              = var.dead_letter_queue_name
  message_retention_seconds         = local.dead_letter_retention
  kms_master_key_id                 = aws_kms_key.queues.arn
  kms_data_key_reuse_period_seconds = 300
}

resource "aws_sqs_queue" "order_placed" {
  name                              = var.queue_name
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  receive_wait_time_seconds         = local.message_wait_seconds
  message_retention_seconds         = local.queue_retention_seconds
  kms_master_key_id                 = aws_kms_key.queues.arn
  kms_data_key_reuse_period_seconds = 300

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letter.arn
    maxReceiveCount     = var.max_receive_count
  })
}

resource "aws_sqs_queue_policy" "order_placed" {
  queue_url = aws_sqs_queue.order_placed.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "PublishFromTheApi"
        Effect    = "Allow"
        Principal = { AWS = var.producer_role_arns }
        Action    = local.producer_actions
        Resource  = aws_sqs_queue.order_placed.arn
      },
      {
        Sid       = "ConsumeFromTheWorker"
        Effect    = "Allow"
        Principal = { AWS = var.consumer_role_arns }
        Action    = local.consumer_actions
        Resource  = aws_sqs_queue.order_placed.arn
      },
      {
        Sid       = "DenyWithoutTls"
        Effect    = "Deny"
        Principal = "*"
        Action    = "sqs:*"
        Resource  = aws_sqs_queue.order_placed.arn
        Condition = local.secure_transport_required
      },
    ]
  })
}

resource "aws_sqs_queue_policy" "dead_letter" {
  queue_url = aws_sqs_queue.dead_letter.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "MoveFailedMessagesFromTheWorker"
        Effect    = "Allow"
        Principal = { AWS = var.consumer_role_arns }
        Action    = local.dead_letter_actions
        Resource  = aws_sqs_queue.dead_letter.arn
      },
      {
        Sid       = "DenyWithoutTls"
        Effect    = "Deny"
        Principal = "*"
        Action    = "sqs:*"
        Resource  = aws_sqs_queue.dead_letter.arn
        Condition = local.secure_transport_required
      },
    ]
  })
}
