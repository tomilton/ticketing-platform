module "platform" {
  source = "../../modules/platform"

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  app                      = var.app
  environment              = var.environment
  region                   = var.region
  availability_zones       = var.availability_zones
  vpc_cidr_block           = var.vpc_cidr_block
  ecr_repository_name      = var.ecr_repository_name
  image_tag                = var.image_tag
  services                 = var.services
  scaling                  = var.scaling
  deletion_protection      = var.deletion_protection
  settings                 = var.settings
  edge                     = var.edge
  observability            = var.observability
  kms_deletion_window_days = var.kms_deletion_window_days
}
