# Internal load balancer of the API. It only receives traffic from the VPC Link of API Gateway and from
# the CloudFront VPC origin of the availability stream; TLS ends at CloudFront and API Gateway, and the
# hop inside the private VPC is HTTP.

locals {
  http_port                  = 80
  cloudfront_prefix_list     = "com.amazonaws.global.cloudfront.origin-facing"
  healthy_threshold          = 2
  unhealthy_threshold        = 3
  health_check_interval      = 15
  deregistration_delay_secs  = 30
  health_check_success_codes = "200"
}

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = local.cloudfront_prefix_list
}

resource "aws_security_group" "vpc_link" {
  # checkov:skip=CKV2_AWS_5: the VPC Link of the edge module attaches it
  name        = "${var.name}-vpc-link"
  description = "VPC Link of API Gateway towards the internal load balancer"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_egress_rule" "vpc_link_to_alb" {
  security_group_id            = aws_security_group.vpc_link.id
  description                  = "HTTP to the internal load balancer"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = local.http_port
  to_port                      = local.http_port
}

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Internal load balancer of the API"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_vpc_link" {
  # checkov:skip=CKV_AWS_260: the source is the VPC Link security group, not 0.0.0.0/0
  security_group_id            = aws_security_group.alb.id
  description                  = "HTTP from the VPC Link of API Gateway"
  referenced_security_group_id = aws_security_group.vpc_link.id
  ip_protocol                  = "tcp"
  from_port                    = local.http_port
  to_port                      = local.http_port
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudfront" {
  # checkov:skip=CKV_AWS_260: the source is the CloudFront origin-facing prefix list, not 0.0.0.0/0
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from the CloudFront VPC origin of the availability stream"
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = local.http_port
  to_port           = local.http_port
}

resource "aws_vpc_security_group_egress_rule" "alb_to_tasks" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP to the API tasks"
  cidr_ipv4         = var.vpc_cidr_block
  ip_protocol       = "tcp"
  from_port         = var.container_port
  to_port           = var.container_port
}

resource "aws_lb" "this" {
  # checkov:skip=CKV_AWS_91: the API logs every request with its traceId; ALB access logs would duplicate them
  # checkov:skip=CKV2_AWS_28: WAF protects CloudFront, the only public entry; this load balancer is internal
  # checkov:skip=CKV2_AWS_20: internal hop inside the private VPC; TLS ends at CloudFront and API Gateway
  # checkov:skip=CKV_AWS_150: var.deletion_protection enables it in production, where the platform validates it; dev and qa are disposable
  name                       = "${var.name}-api"
  internal                   = true
  load_balancer_type         = "application"
  subnets                    = var.subnet_ids
  security_groups            = [aws_security_group.alb.id]
  idle_timeout               = var.idle_timeout_seconds
  drop_invalid_header_fields = true
  enable_deletion_protection = var.deletion_protection
}

resource "aws_lb_target_group" "api" {
  # checkov:skip=CKV_AWS_378: internal hop inside the private VPC; TLS ends at CloudFront and API Gateway
  name                 = "${var.name}-api"
  vpc_id               = var.vpc_id
  port                 = var.container_port
  protocol             = "HTTP"
  target_type          = "ip"
  deregistration_delay = local.deregistration_delay_secs

  health_check {
    path                = var.health_check_path
    matcher             = local.health_check_success_codes
    interval            = local.health_check_interval
    healthy_threshold   = local.healthy_threshold
    unhealthy_threshold = local.unhealthy_threshold
  }
}

resource "aws_lb_listener" "http" {
  # checkov:skip=CKV_AWS_2: internal hop inside the private VPC; TLS ends at CloudFront and API Gateway
  # checkov:skip=CKV_AWS_103: internal hop inside the private VPC; TLS ends at CloudFront and API Gateway
  load_balancer_arn = aws_lb.this.arn
  port              = local.http_port
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}
