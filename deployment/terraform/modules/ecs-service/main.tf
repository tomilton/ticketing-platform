# One Fargate service of the application. The API and the worker are two instances of this module with
# the same image and a different APP_ROLE: only the API sits behind the load balancer, and the worker
# runs on Spot above an On-Demand base.

locals {
  is_api          = var.role == "api"
  container_name  = "app"
  https_port      = 443
  tmp_volume      = "tmp"
  grace_seconds   = 60
  health_interval = 15
  health_timeout  = 5
  health_retries  = 3
  # The JRE image has neither curl nor wget, so the check speaks HTTP over a bash TCP socket.
  health_command = "exec 3<>/dev/tcp/127.0.0.1/${var.container_port} && printf 'GET ${var.health_check_path} HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"

  capacity_strategy = local.is_api ? [
    { provider = "FARGATE", base = 0, weight = 1 },
    ] : [
    { provider = "FARGATE", base = 1, weight = 1 },
    { provider = "FARGATE_SPOT", base = 0, weight = 3 },
  ]
}

resource "aws_cloudwatch_log_group" "this" {
  # checkov:skip=CKV_AWS_158: the logs carry only identifiers and codes (C039); CloudWatch encrypts them at rest
  # checkov:skip=CKV_AWS_338: the retention is chosen per environment to bound the cost
  name              = "/ecs/${var.name}/${var.role}"
  retention_in_days = var.log_retention_days
}

resource "aws_security_group" "task" {
  name        = "${var.name}-${var.role}"
  description = "Tasks of the ${var.role}"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "from_load_balancer" {
  count = local.is_api ? 1 : 0

  security_group_id            = aws_security_group.task.id
  description                  = "Application port from the internal load balancer"
  referenced_security_group_id = var.load_balancer.security_group_id
  ip_protocol                  = "tcp"
  from_port                    = var.container_port
  to_port                      = var.container_port
}

resource "aws_vpc_security_group_egress_rule" "to_interface_endpoints" {
  security_group_id = aws_security_group.task.id
  description       = "HTTPS to the interface endpoints of the VPC"
  cidr_ipv4         = var.vpc_cidr_block
  ip_protocol       = "tcp"
  from_port         = local.https_port
  to_port           = local.https_port
}

resource "aws_vpc_security_group_egress_rule" "to_gateway_endpoints" {
  for_each = var.gateway_prefix_list_ids

  security_group_id = aws_security_group.task.id
  description       = "HTTPS to DynamoDB and S3 through their gateway endpoints"
  prefix_list_id    = each.value
  ip_protocol       = "tcp"
  from_port         = local.https_port
  to_port           = local.https_port
}

resource "aws_ecs_task_definition" "this" {
  family                   = "${var.name}-${var.role}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.cpu)
  memory                   = tostring(var.memory)
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  volume {
    name = local.tmp_volume
  }

  container_definitions = jsonencode([{
    name                   = local.container_name
    image                  = var.image
    essential              = true
    readonlyRootFilesystem = true
    portMappings           = [{ containerPort = var.container_port, protocol = "tcp" }]
    environment            = local.environment
    secrets                = [for name, arn in var.secrets : { name = name, valueFrom = arn }]
    mountPoints            = [{ sourceVolume = local.tmp_volume, containerPath = "/tmp", readOnly = false }]
    healthCheck = {
      command     = ["CMD", "bash", "-c", local.health_command]
      interval    = local.health_interval
      timeout     = local.health_timeout
      retries     = local.health_retries
      startPeriod = local.grace_seconds
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this.name
        awslogs-region        = var.region
        awslogs-stream-prefix = var.role
      }
    }
  }])
}

resource "aws_ecs_service" "this" {
  name                              = "${var.name}-${var.role}"
  cluster                           = var.cluster_arn
  task_definition                   = aws_ecs_task_definition.this.arn
  desired_count                     = var.min_capacity
  health_check_grace_period_seconds = local.is_api ? local.grace_seconds : null
  propagate_tags                    = "SERVICE"

  dynamic "capacity_provider_strategy" {
    for_each = local.capacity_strategy

    content {
      capacity_provider = capacity_provider_strategy.value.provider
      base              = capacity_provider_strategy.value.base
      weight            = capacity_provider_strategy.value.weight
    }
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.task.id]
    assign_public_ip = false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "load_balancer" {
    for_each = local.is_api ? [var.load_balancer] : []

    content {
      target_group_arn = load_balancer.value.target_group_arn
      container_name   = local.container_name
      container_port   = var.container_port
    }
  }

  lifecycle {
    ignore_changes = [desired_count]
  }
}
