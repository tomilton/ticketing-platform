# One Fargate service of the application. The API and the worker are two instances of this module with
# the same image and a different APP_ROLE: only the API sits behind the load balancer, and the worker
# runs on Spot above an On-Demand base.

resource "aws_cloudwatch_log_group" "this" {
  # checkov:skip=CKV_AWS_158: the logs carry only identifiers and codes (C039); CloudWatch encrypts them at rest
  # checkov:skip=CKV_AWS_338: the retention is chosen per environment to bound the cost
  name              = "/ecs/${var.name}/${var.role}"
  retention_in_days = var.log_retention_days
}
