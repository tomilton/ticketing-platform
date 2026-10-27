variable "name" {
  description = "Prefix of every resource name, for example ticketing-dev."
  type        = string
}

variable "vpc_id" {
  description = "VPC of the load balancer."
  type        = string
}

variable "vpc_cidr_block" {
  description = "CIDR block of the VPC, where the API tasks run."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets of the load balancer, one per availability zone."
  type        = list(string)
}

variable "container_port" {
  description = "Port of the API container, the same of APP_PORT."
  type        = number
}

variable "health_check_path" {
  description = "Path of the health check of the API."
  type        = string
}

variable "idle_timeout_seconds" {
  description = "Seconds an idle connection stays open; long enough for the availability stream."
  type        = number
}

variable "deletion_protection" {
  description = "Whether the load balancer is protected against deletion; true in production."
  type        = bool
}
