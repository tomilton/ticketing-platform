output "alb_arn" {
  description = "ARN of the internal load balancer, the CloudFront VPC origin."
  value       = aws_lb.this.arn
}

output "alb_arn_suffix" {
  description = "Suffix of the ARN of the load balancer, the dimension of its CloudWatch metrics."
  value       = aws_lb.this.arn_suffix
}

output "listener_arn" {
  description = "ARN of the listener, the integration target of API Gateway."
  value       = aws_lb_listener.http.arn
}

output "target_group_arn" {
  description = "ARN of the target group of the API tasks."
  value       = aws_lb_target_group.api.arn
}

output "target_group_arn_suffix" {
  description = "Suffix of the ARN of the target group, the dimension of the request count per target."
  value       = aws_lb_target_group.api.arn_suffix
}

output "alb_security_group_id" {
  description = "Security group of the load balancer; the API tasks accept traffic only from it."
  value       = aws_security_group.alb.id
}

output "vpc_link_security_group_id" {
  description = "Security group of the VPC Link of API Gateway."
  value       = aws_security_group.vpc_link.id
}
