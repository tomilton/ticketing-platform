# Public entry of the platform. CloudFront, protected by WAF, sends the availability stream straight to
# the internal load balancer through a VPC origin, because API Gateway closes a connection after 30 s,
# and the rest of the API to API Gateway. Nothing is cached: every answer depends on live inventory.

locals {
  api_gateway_origin     = "api-gateway"
  stream_origin          = "availability-stream"
  http_port              = 80
  https_port             = 443
  keepalive_seconds      = 60
  all_methods            = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
  read_methods           = ["GET", "HEAD"]
  caching_disabled       = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
  all_viewer_except_host = "b689b0a8-53d0-40ab-baf2-68738e2966ac"
  security_headers       = "67f7725c-6f97-4210-82d7-5512b31e9d03"
  uses_default_domain    = var.certificate_arn == null
}

resource "aws_cloudfront_vpc_origin" "stream" {
  vpc_origin_endpoint_config {
    name                   = "${var.name}-alb"
    arn                    = var.alb_arn
    http_port              = local.http_port
    https_port             = local.https_port
    origin_protocol_policy = "http-only"

    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
}

resource "aws_cloudfront_distribution" "this" {
  # checkov:skip=CKV_AWS_86: requests are traced by API Gateway access logs and the application logs
  # checkov:skip=CKV_AWS_174: with the default domain CloudFront fixes the TLS policy; an ACM certificate enables TLSv1.2_2021
  # checkov:skip=CKV_AWS_310: there is a single API origin per path; failover would need a second region
  # checkov:skip=CKV_AWS_374: the platform sells to any country; WAF limits abuse by rate instead
  # checkov:skip=CKV2_AWS_42: the default CloudFront domain is used until a domain and its certificate exist
  # checkov:skip=CKV_AWS_305: the distribution serves an API; there is no root document
  # checkov:skip=CKV2_AWS_32: both behaviors attach the managed SecurityHeadersPolicy through local.security_headers
  # checkov:skip=CKV2_AWS_47: the web ACL includes AWSManagedRulesKnownBadInputsRuleSet, which covers Log4j, in a dynamic block
  enabled         = true
  is_ipv6_enabled = true
  comment         = "${var.name} API and availability stream"
  price_class     = var.price_class
  aliases         = var.aliases
  web_acl_id      = aws_wafv2_web_acl.this.arn

  origin {
    origin_id   = local.api_gateway_origin
    domain_name = replace(aws_apigatewayv2_api.this.api_endpoint, "https://", "")

    custom_origin_config {
      http_port              = local.http_port
      https_port             = local.https_port
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  origin {
    origin_id   = local.stream_origin
    domain_name = var.alb_dns_name

    vpc_origin_config {
      vpc_origin_id            = aws_cloudfront_vpc_origin.stream.id
      origin_read_timeout      = var.stream_read_timeout_seconds
      origin_keepalive_timeout = local.keepalive_seconds
    }
  }

  ordered_cache_behavior {
    path_pattern               = var.stream_path_pattern
    target_origin_id           = local.stream_origin
    viewer_protocol_policy     = "https-only"
    allowed_methods            = local.read_methods
    cached_methods             = local.read_methods
    compress                   = false
    cache_policy_id            = local.caching_disabled
    origin_request_policy_id   = local.all_viewer_except_host
    response_headers_policy_id = local.security_headers
  }

  default_cache_behavior {
    target_origin_id           = local.api_gateway_origin
    viewer_protocol_policy     = "https-only"
    allowed_methods            = local.all_methods
    cached_methods             = local.read_methods
    compress                   = true
    cache_policy_id            = local.caching_disabled
    origin_request_policy_id   = local.all_viewer_except_host
    response_headers_policy_id = local.security_headers
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = local.uses_default_domain
    acm_certificate_arn            = var.certificate_arn
    ssl_support_method             = local.uses_default_domain ? null : "sni-only"
    minimum_protocol_version       = local.uses_default_domain ? "TLSv1" : "TLSv1.2_2021"
  }
}
