mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }

  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/mock"
    }
  }

  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:us-east-1:123456789012:log-group:mock"
    }
  }
}

variables {
  name                    = "ticketing-test"
  region                  = "us-east-1"
  cidr_block              = "10.20.0.0/16"
  availability_zones      = ["us-east-1a", "us-east-1b", "us-east-1c"]
  flow_log_retention_days = 30
}

run "spans_three_zones_with_private_subnets_only" {
  command = plan

  assert {
    condition     = length(aws_subnet.private) == 3
    error_message = "There must be one private subnet per availability zone."
  }

  assert {
    condition     = toset([for subnet in aws_subnet.private : subnet.availability_zone]) == toset(var.availability_zones)
    error_message = "The private subnets must use the three availability zones."
  }

  assert {
    condition     = alltrue([for subnet in aws_subnet.private : subnet.map_public_ip_on_launch == false])
    error_message = "The private subnets must not assign public addresses."
  }

  assert {
    condition     = alltrue([for subnet in aws_subnet.private : endswith(subnet.cidr_block, "/20")])
    error_message = "Each private subnet must be a /20."
  }
}

run "has_no_nat_and_no_internet_route" {
  command = apply

  assert {
    condition     = length(aws_route_table.private.route) == 0
    error_message = "The private route table must not route to the internet or to a NAT gateway."
  }
}

run "has_the_seven_endpoints_of_the_application" {
  command = plan

  assert {
    condition     = length(aws_vpc_endpoint.gateway) + length(aws_vpc_endpoint.interface) == 7
    error_message = "There must be seven VPC endpoints."
  }

  assert {
    condition = toset(concat(keys(aws_vpc_endpoint.gateway), keys(aws_vpc_endpoint.interface))) == toset([
      "dynamodb", "s3", "sqs", "ecr.api", "ecr.dkr", "logs", "sts"
    ])
    error_message = "The endpoints must be DynamoDB, S3, SQS, ECR API, ECR DKR, CloudWatch Logs and STS."
  }

  assert {
    condition     = alltrue([for endpoint in aws_vpc_endpoint.interface : endpoint.private_dns_enabled])
    error_message = "The interface endpoints must resolve the public service names inside the VPC."
  }

  assert {
    condition     = aws_vpc_endpoint.gateway["dynamodb"].service_name == "com.amazonaws.us-east-1.dynamodb"
    error_message = "The DynamoDB endpoint must belong to the region of the VPC."
  }
}

run "rejects_a_number_of_zones_other_than_three" {
  command = plan

  variables {
    availability_zones = ["us-east-1a", "us-east-1b"]
  }

  expect_failures = [var.availability_zones]
}
