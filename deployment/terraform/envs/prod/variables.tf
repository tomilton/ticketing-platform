# The types and validations live in modules/platform; here they only receive terraform.tfvars.

variable "app" {
  description = "Name of the application, the prefix of every resource and the app tag."
  type        = string
}

variable "environment" {
  description = "Environment: dev, qa or prod."
  type        = string
}

variable "owner" {
  description = "Team that owns the resources, the owner tag."
  type        = string
}

variable "cost_center" {
  description = "Cost center billed for the resources, the cost-center tag."
  type        = string
}

variable "region" {
  description = "AWS region of the platform."
  type        = string
}

variable "availability_zones" {
  description = "The three availability zones of the region."
  type        = list(string)
}

variable "vpc_cidr_block" {
  description = "CIDR block of the VPC."
  type        = string
}

variable "ecr_repository_name" {
  description = "ECR repository of the image of the application."
  type        = string
}

variable "image_tag" {
  description = "Tag of the image to deploy."
  type        = string
}

variable "services" {
  description = "Size and scaling range of the API and the worker."
  type        = any
}

variable "scaling" {
  description = "Scaling targets of the API and the worker."
  type        = any
}

variable "deletion_protection" {
  description = "Protection against deletion of the tables and the load balancer."
  type        = bool
}

variable "settings" {
  description = "Business and tuning settings of the application."
  type        = map(string)
}

variable "edge" {
  description = "Settings of the public entry."
  type        = any
}

variable "observability" {
  description = "Log retention, alarm thresholds, alert addresses and monthly budget."
  type        = any
}

variable "kms_deletion_window_days" {
  description = "Days a deleted key waits before it is destroyed."
  type        = number
}
