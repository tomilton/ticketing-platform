# WAF of CloudFront, which must live in us-east-1: AWS managed rules for common attacks and known bad
# inputs, and a rate limit per IP that blocks a client sending too many requests.

locals {
  managed_rule_groups = ["AWSManagedRulesCommonRuleSet", "AWSManagedRulesKnownBadInputsRuleSet"]
  rate_rule_priority  = length(local.managed_rule_groups)
}

resource "aws_wafv2_web_acl" "this" {
  provider = aws.us_east_1

  name  = "${var.name}-edge"
  scope = "CLOUDFRONT"

  default_action {
    allow {}
  }

  dynamic "rule" {
    for_each = local.managed_rule_groups

    content {
      name     = rule.value
      priority = rule.key

      override_action {
        none {}
      }

      statement {
        managed_rule_group_statement {
          vendor_name = "AWS"
          name        = rule.value
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        sampled_requests_enabled   = true
        metric_name                = rule.value
      }
    }
  }

  rule {
    name     = "rate-limit-per-ip"
    priority = local.rate_rule_priority

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.waf_rate_limit_per_ip
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
      metric_name                = "rate-limit-per-ip"
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    sampled_requests_enabled   = true
    metric_name                = "${var.name}-edge"
  }
}

resource "aws_cloudwatch_log_group" "waf" {
  # checkov:skip=CKV_AWS_158: WAF logs hold request metadata only; CloudWatch encrypts them at rest
  # checkov:skip=CKV_AWS_338: the retention is chosen per environment to bound the cost
  provider = aws.us_east_1

  name              = "aws-waf-logs-${var.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_wafv2_web_acl_logging_configuration" "this" {
  provider = aws.us_east_1

  resource_arn            = aws_wafv2_web_acl.this.arn
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]
}
