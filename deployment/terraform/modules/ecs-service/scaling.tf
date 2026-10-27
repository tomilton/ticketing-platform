# Scaling by role: the API follows its CPU and the requests per task; the worker follows the visible
# messages of the queue, adding tasks in steps and removing one when the queue is empty.

locals {
  scale_out_cooldown  = 60
  scale_in_cooldown   = 300
  queue_metric_period = 60
  empty_queue_periods = 5
  backlog_steps       = [1, 2, 4]
  messages_per_step   = var.scaling.messages_per_scale_up
  queue_metric_name   = "ApproximateNumberOfMessagesVisible"
  steps_per_band      = 5
}

resource "aws_appautoscaling_target" "this" {
  service_namespace  = "ecs"
  resource_id        = "service/${var.cluster_name}/${aws_ecs_service.this.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.min_capacity
  max_capacity       = var.max_capacity
}

resource "aws_appautoscaling_policy" "cpu" {
  count = local.is_api ? 1 : 0

  name               = "${var.name}-${var.role}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  resource_id        = aws_appautoscaling_target.this.resource_id
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value       = var.scaling.cpu_percent
    scale_out_cooldown = local.scale_out_cooldown
    scale_in_cooldown  = local.scale_in_cooldown

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_policy" "requests" {
  count = local.is_api ? 1 : 0

  name               = "${var.name}-${var.role}-requests"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  resource_id        = aws_appautoscaling_target.this.resource_id
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value       = var.scaling.requests_per_target
    scale_out_cooldown = local.scale_out_cooldown
    scale_in_cooldown  = local.scale_in_cooldown

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${var.load_balancer.alb_arn_suffix}/${var.load_balancer.target_group_arn_suffix}"
    }
  }
}

resource "aws_appautoscaling_policy" "backlog_out" {
  count = local.is_api ? 0 : 1

  name               = "${var.name}-${var.role}-backlog-out"
  policy_type        = "StepScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  resource_id        = aws_appautoscaling_target.this.resource_id
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension

  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = local.scale_out_cooldown
    metric_aggregation_type = "Maximum"

    dynamic "step_adjustment" {
      for_each = local.backlog_steps

      content {
        metric_interval_lower_bound = step_adjustment.key * local.messages_per_step * local.steps_per_band
        metric_interval_upper_bound = step_adjustment.key == length(local.backlog_steps) - 1 ? null : (step_adjustment.key + 1) * local.messages_per_step * local.steps_per_band
        scaling_adjustment          = step_adjustment.value
      }
    }
  }
}

resource "aws_appautoscaling_policy" "backlog_in" {
  count = local.is_api ? 0 : 1

  name               = "${var.name}-${var.role}-backlog-in"
  policy_type        = "StepScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  resource_id        = aws_appautoscaling_target.this.resource_id
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension

  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = local.scale_in_cooldown
    metric_aggregation_type = "Maximum"

    step_adjustment {
      metric_interval_upper_bound = 0
      scaling_adjustment          = -1
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "backlog_high" {
  count = local.is_api ? 0 : 1

  alarm_name          = "${var.name}-${var.role}-backlog-high"
  alarm_description   = "Visible messages of the queue above one step; the worker adds tasks"
  namespace           = "AWS/SQS"
  metric_name         = local.queue_metric_name
  dimensions          = { QueueName = var.queue.name }
  statistic           = "Maximum"
  period              = local.queue_metric_period
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = local.messages_per_step
  alarm_actions       = [aws_appautoscaling_policy.backlog_out[0].arn]
}

resource "aws_cloudwatch_metric_alarm" "backlog_empty" {
  count = local.is_api ? 0 : 1

  alarm_name          = "${var.name}-${var.role}-backlog-empty"
  alarm_description   = "The queue stayed empty; the worker removes a task"
  namespace           = "AWS/SQS"
  metric_name         = local.queue_metric_name
  dimensions          = { QueueName = var.queue.name }
  statistic           = "Maximum"
  period              = local.queue_metric_period
  evaluation_periods  = local.empty_queue_periods
  comparison_operator = "LessThanOrEqualToThreshold"
  threshold           = 0
  alarm_actions       = [aws_appautoscaling_policy.backlog_in[0].arn]
}
