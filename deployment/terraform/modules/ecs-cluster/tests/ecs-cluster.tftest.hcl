mock_provider "aws" {}

variables {
  name = "ticketing-test"
}

run "enables_container_insights_and_both_fargate_providers" {
  command = plan

  assert {
    condition     = one(aws_ecs_cluster.this.setting).value == "enabled"
    error_message = "The cluster must have Container Insights."
  }

  assert {
    condition     = toset(aws_ecs_cluster_capacity_providers.this.capacity_providers) == toset(["FARGATE", "FARGATE_SPOT"])
    error_message = "The cluster must offer Fargate On-Demand and Spot."
  }

  assert {
    condition     = one(aws_ecs_cluster_capacity_providers.this.default_capacity_provider_strategy).capacity_provider == "FARGATE"
    error_message = "A service without its own strategy runs On-Demand."
  }
}
