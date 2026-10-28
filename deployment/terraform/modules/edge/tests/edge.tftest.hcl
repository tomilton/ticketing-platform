mock_provider "aws" {
  mock_resource "aws_apigatewayv2_api" {
    defaults = {
      api_endpoint = "https://abc123.execute-api.us-east-1.amazonaws.com"
    }
  }

  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:us-east-1:123456789012:log-group:mock"
    }
  }
}

mock_provider "aws" {
  alias = "us_east_1"

  mock_resource "aws_wafv2_web_acl" {
    defaults = {
      arn = "arn:aws:wafv2:us-east-1:123456789012:global/webacl/mock/1"
    }
  }

  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:us-east-1:123456789012:log-group:aws-waf-logs-mock"
    }
  }
}

variables {
  name                        = "ticketing-test"
  subnet_ids                  = ["subnet-a", "subnet-b", "subnet-c"]
  vpc_link_security_group_id  = "sg-vpc-link"
  alb_arn                     = "arn:aws:elasticloadbalancing:us-east-1:123456789012:loadbalancer/app/ticketing-test-api/1"
  alb_dns_name                = "internal-ticketing-test-api-1.us-east-1.elb.amazonaws.com"
  alb_listener_arn            = "arn:aws:elasticloadbalancing:us-east-1:123456789012:listener/app/ticketing-test-api/1/2"
  base_path                   = "/api/v1"
  stream_path_pattern         = "/api/v1/events/*/availability/stream"
  stream_read_timeout_seconds = 60
  throttling = {
    rate         = 1000
    burst        = 2000
    orders_rate  = 200
    orders_burst = 400
  }
  jwt = {
    enabled  = false
    issuer   = "https://issuer.example.com"
    audience = ["ticketing"]
  }
  waf_rate_limit_per_ip = 2000
  certificate_arn       = null
  aliases               = []
  price_class           = "PriceClass_100"
  log_retention_days    = 30
}

run "sends_the_stream_to_the_load_balancer_without_cache" {
  command = apply

  assert {
    condition     = one(aws_cloudfront_distribution.this.ordered_cache_behavior).path_pattern == "/api/v1/events/*/availability/stream"
    error_message = "The availability stream must have its own behavior."
  }

  assert {
    condition     = one(aws_cloudfront_distribution.this.ordered_cache_behavior).target_origin_id == "availability-stream"
    error_message = "The stream must go to the VPC origin of the load balancer, not to API Gateway."
  }

  assert {
    condition     = one(aws_cloudfront_distribution.this.ordered_cache_behavior).cache_policy_id == "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
    error_message = "The stream must not be cached."
  }

  assert {
    condition     = one([for origin in aws_cloudfront_distribution.this.origin : origin if origin.origin_id == "availability-stream"]).vpc_origin_config[0].vpc_origin_id == aws_cloudfront_vpc_origin.stream.id
    error_message = "The stream origin must be the VPC origin of the internal load balancer."
  }

  assert {
    condition     = aws_cloudfront_vpc_origin.stream.vpc_origin_endpoint_config[0].arn == var.alb_arn
    error_message = "The VPC origin must point to the internal load balancer."
  }
}

run "sends_the_rest_of_the_api_to_api_gateway_through_the_vpc_link" {
  command = apply

  assert {
    condition     = one(aws_cloudfront_distribution.this.default_cache_behavior).target_origin_id == "api-gateway"
    error_message = "Every other path must go to API Gateway."
  }

  assert {
    condition     = one([for origin in aws_cloudfront_distribution.this.origin : origin if origin.origin_id == "api-gateway"]).domain_name == "abc123.execute-api.us-east-1.amazonaws.com"
    error_message = "The API Gateway origin must be the domain of the HTTP API."
  }

  assert {
    condition     = aws_apigatewayv2_integration.alb.connection_type == "VPC_LINK" && aws_apigatewayv2_integration.alb.integration_uri == var.alb_listener_arn
    error_message = "API Gateway must reach the internal load balancer through the VPC Link."
  }

  assert {
    condition     = one([for setting in aws_apigatewayv2_stage.default.route_settings : setting if setting.route_key == "POST /api/v1/orders"]).throttling_rate_limit == 200
    error_message = "POST /orders must have its own throttling."
  }
}

run "protects_cloudfront_with_managed_rules_and_a_rate_limit" {
  command = apply

  assert {
    condition     = aws_wafv2_web_acl.this.scope == "CLOUDFRONT" && aws_cloudfront_distribution.this.web_acl_id == aws_wafv2_web_acl.this.arn
    error_message = "The distribution must be protected by a CloudFront web ACL."
  }

  assert {
    condition     = toset([for rule in aws_wafv2_web_acl.this.rule : rule.name]) == toset(["AWSManagedRulesCommonRuleSet", "AWSManagedRulesKnownBadInputsRuleSet", "rate-limit-per-ip"])
    error_message = "WAF must have the common and known bad inputs rule groups and a rate limit per IP."
  }

  assert {
    condition     = one([for rule in aws_wafv2_web_acl.this.rule : rule if rule.name == "rate-limit-per-ip"]).statement[0].rate_based_statement[0].limit == 2000
    error_message = "The rate limit per IP must be the configured one."
  }
}

run "has_no_authorizer_until_jwt_is_enabled" {
  command = apply

  assert {
    condition     = length(aws_apigatewayv2_authorizer.jwt) == 0 && alltrue([for route in aws_apigatewayv2_route.this : route.authorization_type == "NONE"])
    error_message = "Without var.jwt.enabled no route uses the authorizer."
  }
}

run "protects_every_route_when_jwt_is_enabled" {
  command = apply

  variables {
    jwt = {
      enabled  = true
      issuer   = "https://issuer.example.com"
      audience = ["ticketing"]
    }
  }

  assert {
    condition     = length(aws_apigatewayv2_authorizer.jwt) == 1 && alltrue([for route in aws_apigatewayv2_route.this : route.authorization_type == "JWT"])
    error_message = "With JWT every route must use the authorizer."
  }

  assert {
    condition = toset([for route in aws_apigatewayv2_route.public : route.route_key]) == toset([
      "GET /api/v1/events", "GET /api/v1/events/{proxy+}", "POST /api/v1/webhooks/payments"
    ]) && alltrue([for route in aws_apigatewayv2_route.public : route.authorization_type == "NONE"])
    error_message = "The catalog, the availability and the signed webhook must stay public."
  }
}
