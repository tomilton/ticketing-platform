# Plans the environment with its terraform.tfvars and a mock provider: the values pass every validation
# of the modules without an AWS account.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

mock_provider "aws" {
  alias = "us_east_1"
}

run "plans_the_environment_with_its_values" {
  command = plan

  assert {
    condition     = var.environment == "prod" && var.deletion_protection
    error_message = "Production protects its tables and load balancer against deletion."
  }

  assert {
    condition     = alltrue([for service in values(var.services) : service.min_capacity >= 2])
    error_message = "Production runs at least two tasks of the API and two of the worker."
  }

  assert {
    condition     = length(module.platform.service_names) == 2
    error_message = "The environment runs the API and the worker as separate services."
  }
}
