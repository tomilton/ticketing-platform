# Every resource carries the app, env, owner and cost-center tags. WAF of CloudFront lives in us-east-1.
locals {
  tags = {
    app           = var.app
    env           = var.environment
    owner         = var.owner
    "cost-center" = var.cost_center
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}

provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = local.tags
  }
}
