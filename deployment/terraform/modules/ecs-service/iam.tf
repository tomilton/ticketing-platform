# Roles of least privilege. The execution role only pulls the image and writes the logs; the task role
# only touches the tables, queues and keys its role uses, by ARN.

locals {
  tables_by_role = {
    api    = ["events", "inventory", "orders", "order_audit", "idempotency", "customer_limits"]
    worker = ["events", "inventory", "orders", "order_audit", "customer_limits", "scheduler_locks"]
  }
  table_actions = [
    "dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query", "dynamodb:PutItem", "dynamodb:UpdateItem",
    "dynamodb:DeleteItem", "dynamodb:ConditionCheckItem", "dynamodb:DescribeTable",
  ]
  queue_actions_by_role = {
    api    = ["sqs:SendMessage", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
    worker = ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
  }
  role_table_arns = [for table in local.tables_by_role[var.role] : var.table_arns[table]]

  task_statements = concat(
    [
      {
        Sid      = "Tables"
        Effect   = "Allow"
        Action   = local.table_actions
        Resource = concat(local.role_table_arns, [for arn in local.role_table_arns : "${arn}/index/*"])
      },
      {
        Sid      = "OrderPlacedQueue"
        Effect   = "Allow"
        Action   = local.queue_actions_by_role[var.role]
        Resource = [var.queue.arn]
      },
      {
        Sid      = "EncryptionKeys"
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
        Resource = var.kms_key_arns
      },
    ],
    var.role == "worker" ? [{
      Sid      = "DeadLetterQueue"
      Effect   = "Allow"
      Action   = ["sqs:SendMessage", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
      Resource = [var.queue.dead_letter_queue_arn]
    }] : [],
  )

  ecs_tasks_trust = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role" "execution" {
  name               = "${var.name}-${var.role}-execution"
  assume_role_policy = local.ecs_tasks_trust
}

resource "aws_iam_role_policy" "execution" {
  name = "pull-image-and-write-logs"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuthorization"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = ["*"]
      },
      {
        Sid      = "PullImage"
        Effect   = "Allow"
        Action   = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"]
        Resource = [var.ecr_repository_arn]
      },
      {
        Sid      = "WriteLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = ["${aws_cloudwatch_log_group.this.arn}:*"]
      },
      {
        Sid      = "ReadSecrets"
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = values(var.secrets)
      },
      {
        Sid      = "DecryptSecrets"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = var.kms_key_arns
      },
    ]
  })
}

resource "aws_iam_role" "task" {
  name               = "${var.name}-${var.role}-task"
  assume_role_policy = local.ecs_tasks_trust
}

resource "aws_iam_role_policy" "task" {
  name = "${var.role}-resources"
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = local.task_statements
  })
}
