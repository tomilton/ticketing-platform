# HTTP API in front of the internal load balancer through a VPC Link, with throttling for the whole
# API and a stricter limit for POST /orders. With var.jwt.enabled every route needs a valid token,
# except the public ones: the catalog and the availability, and the webhook of the payment gateway,
# which its HMAC signature authenticates. API Gateway picks the most specific route, so the public
# routes win over the catch-all one.

locals {
  orders_route = "POST ${var.base_path}/orders"
  routes       = ["ANY ${var.base_path}/{proxy+}", local.orders_route]
  public_routes = [
    "GET ${var.base_path}/events",
    "GET ${var.base_path}/events/{proxy+}",
    "POST ${var.base_path}/webhooks/payments",
  ]
}

resource "aws_apigatewayv2_api" "this" {
  name          = "${var.name}-api"
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_vpc_link" "this" {
  name               = "${var.name}-alb"
  subnet_ids         = var.subnet_ids
  security_group_ids = [var.vpc_link_security_group_id]
}

resource "aws_apigatewayv2_integration" "alb" {
  api_id             = aws_apigatewayv2_api.this.id
  integration_type   = "HTTP_PROXY"
  integration_method = "ANY"
  integration_uri    = var.alb_listener_arn
  connection_type    = "VPC_LINK"
  connection_id      = aws_apigatewayv2_vpc_link.this.id
}

resource "aws_apigatewayv2_authorizer" "jwt" {
  count = var.jwt.enabled ? 1 : 0

  api_id           = aws_apigatewayv2_api.this.id
  name             = "${var.name}-jwt"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  jwt_configuration {
    issuer   = var.jwt.issuer
    audience = var.jwt.audience
  }
}

resource "aws_apigatewayv2_route" "this" {
  for_each = toset(local.routes)

  # checkov:skip=CKV_AWS_309: every environment sets var.jwt.enabled; the flag only exists for the module tests
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = each.value
  target             = "integrations/${aws_apigatewayv2_integration.alb.id}"
  authorization_type = var.jwt.enabled ? "JWT" : "NONE"
  authorizer_id      = var.jwt.enabled ? aws_apigatewayv2_authorizer.jwt[0].id : null
}

resource "aws_apigatewayv2_route" "public" {
  for_each = toset(local.public_routes)

  # checkov:skip=CKV_AWS_309: public by design; the webhook is authenticated by its HMAC signature in the API
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = each.value
  target             = "integrations/${aws_apigatewayv2_integration.alb.id}"
  authorization_type = "NONE"
}

resource "aws_cloudwatch_log_group" "api_access" {
  # checkov:skip=CKV_AWS_158: access logs carry no personal data; CloudWatch encrypts them at rest
  # checkov:skip=CKV_AWS_338: the retention is chosen per environment to bound the cost
  name              = "/apigateway/${var.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = "$default"
  auto_deploy = true

  default_route_settings {
    throttling_rate_limit  = var.throttling.rate
    throttling_burst_limit = var.throttling.burst
  }

  route_settings {
    route_key              = local.orders_route
    throttling_rate_limit  = var.throttling.orders_rate
    throttling_burst_limit = var.throttling.orders_burst
  }

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api_access.arn
    format = jsonencode({
      requestId = "$context.requestId"
      method    = "$context.httpMethod"
      route     = "$context.routeKey"
      status    = "$context.status"
      latency   = "$context.responseLatency"
    })
  }

  depends_on = [aws_apigatewayv2_route.this, aws_apigatewayv2_route.public]
}
