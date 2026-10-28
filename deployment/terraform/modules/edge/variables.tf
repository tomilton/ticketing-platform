variable "name" {
  description = "Prefix of every resource name, for example ticketing-dev."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets of the VPC Link."
  type        = list(string)
}

variable "vpc_link_security_group_id" {
  description = "Security group of the VPC Link, the only one the load balancer admits besides CloudFront."
  type        = string
}

variable "alb_arn" {
  description = "ARN of the internal load balancer, the VPC origin of the availability stream."
  type        = string
}

variable "alb_dns_name" {
  description = "DNS name of the internal load balancer, the domain of the VPC origin."
  type        = string
}

variable "alb_listener_arn" {
  description = "ARN of the listener of the internal load balancer, the integration of API Gateway."
  type        = string
}

variable "base_path" {
  description = "Base path of the API, the value of ROUTES_BASE_PATH."
  type        = string
}

variable "stream_path_pattern" {
  description = "Path of the availability stream that CloudFront sends straight to the load balancer."
  type        = string
}

variable "stream_read_timeout_seconds" {
  description = "Seconds CloudFront waits for the next byte of the stream; the heartbeat must be shorter."
  type        = number
}

variable "throttling" {
  description = "Requests per second and burst of the API, and the stricter ones of POST /orders."
  type = object({
    rate         = number
    burst        = number
    orders_rate  = number
    orders_burst = number
  })
}

variable "jwt" {
  description = "JWT authorizer of API Gateway for every route but the public ones; enabled in every environment."
  type = object({
    enabled  = bool
    issuer   = string
    audience = list(string)
  })
}

variable "waf_rate_limit_per_ip" {
  description = "Requests per IP in five minutes before WAF blocks it."
  type        = number
}

variable "certificate_arn" {
  description = "ACM certificate in us-east-1 for the aliases; null uses the default CloudFront domain."
  type        = string
}

variable "aliases" {
  description = "Domain names of the distribution; empty with the default CloudFront domain."
  type        = list(string)
}

variable "price_class" {
  description = "Price class of the distribution, for example PriceClass_100."
  type        = string
}

variable "log_retention_days" {
  description = "Days the access logs of API Gateway and WAF are kept."
  type        = number
}
