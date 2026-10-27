output "cluster_arn" {
  description = "ARN of the cluster."
  value       = aws_ecs_cluster.this.arn
}

output "cluster_name" {
  description = "Name of the cluster."
  value       = aws_ecs_cluster.this.name
}
