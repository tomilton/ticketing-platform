variable "name" {
  description = "Prefix of every resource name, for example ticketing-dev."
  type        = string
}

variable "region" {
  description = "AWS region of the VPC endpoints."
  type        = string
}

variable "cidr_block" {
  description = "CIDR block of the VPC; a /16 leaves room for three /20 private subnets."
  type        = string

  validation {
    condition     = can(cidrnetmask(var.cidr_block))
    error_message = "The CIDR block must be a valid IPv4 CIDR."
  }
}

variable "availability_zones" {
  description = "Exactly three availability zones of the region."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) == 3
    error_message = "The network spans exactly three availability zones."
  }
}

variable "flow_log_retention_days" {
  description = "Days the VPC flow logs are kept."
  type        = number
}
