mock_provider "aws" {
  mock_data "aws_ec2_managed_prefix_list" {
    defaults = {
      id = "pl-cloudfront"
    }
  }
}

variables {
  name                 = "ticketing-test"
  vpc_id               = "vpc-test"
  vpc_cidr_block       = "10.20.0.0/16"
  subnet_ids           = ["subnet-a", "subnet-b", "subnet-c"]
  container_port       = 8080
  health_check_path    = "/actuator/health"
  idle_timeout_seconds = 4000
  deletion_protection  = false
}

run "is_internal_with_a_long_idle_timeout_for_the_stream" {
  command = plan

  assert {
    condition     = aws_lb.this.internal
    error_message = "The load balancer must be internal."
  }

  assert {
    condition     = aws_lb.this.idle_timeout == 4000
    error_message = "The idle timeout must allow the availability stream."
  }

  assert {
    condition     = aws_lb.this.drop_invalid_header_fields
    error_message = "The load balancer must drop invalid headers."
  }
}

run "accepts_only_the_vpc_link_and_cloudfront" {
  command = plan

  assert {
    condition     = aws_vpc_security_group_ingress_rule.alb_from_cloudfront.prefix_list_id == "pl-cloudfront"
    error_message = "CloudFront reaches the load balancer through its origin-facing prefix list."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.alb_from_cloudfront.cidr_ipv4 == null && aws_vpc_security_group_ingress_rule.alb_from_vpc_link.cidr_ipv4 == null
    error_message = "No ingress rule may open the load balancer to an address range."
  }

  assert {
    condition     = aws_vpc_security_group_egress_rule.alb_to_tasks.from_port == 8080 && aws_vpc_security_group_egress_rule.alb_to_tasks.cidr_ipv4 == "10.20.0.0/16"
    error_message = "The load balancer only talks to the API port inside the VPC."
  }
}

run "checks_the_health_of_the_api_tasks" {
  command = plan

  assert {
    condition     = aws_lb_target_group.api.target_type == "ip" && aws_lb_target_group.api.port == 8080
    error_message = "The Fargate tasks register by IP on the API port."
  }

  assert {
    condition     = one(aws_lb_target_group.api.health_check).path == "/actuator/health"
    error_message = "The health check uses the actuator."
  }
}
