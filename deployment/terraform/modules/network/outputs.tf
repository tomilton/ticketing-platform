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
