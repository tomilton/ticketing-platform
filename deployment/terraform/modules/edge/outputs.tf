output "distribution_domain_name" {
  description = "Public domain of the platform."
  value       = aws_cloudfront_distribution.this.domain_name
}

output "distribution_id" {
  description = "Identifier of the CloudFront distribution."
  value       = aws_cloudfront_distribution.this.id
}

output "api_id" {
  description = "Identifier of the HTTP API, the dimension of its CloudWatch metrics."
  value       = aws_apigatewayv2_api.this.id
}
