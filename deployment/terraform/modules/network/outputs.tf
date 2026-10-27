output "vpc_id" {
  description = "Identifier of the VPC."
  value       = aws_vpc.this.id
}

output "vpc_cidr_block" {
  description = "CIDR block of the VPC."
  value       = aws_vpc.this.cidr_block
}

output "private_subnet_ids" {
  description = "Private subnets of the tasks and the internal load balancer, one per availability zone."
  value       = aws_subnet.private[*].id
}

output "gateway_prefix_list_ids" {
  description = "Prefix list of each gateway endpoint (dynamodb and s3), the egress destinations of the tasks."
  value       = { for service, endpoint in aws_vpc_endpoint.gateway : service => endpoint.prefix_list_id }
}
