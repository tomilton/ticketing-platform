# Remote state in S3: encrypted, versioned by the bucket and locked with a lock file. The bucket is a
# prerequisite of the account and is given at init: terraform init -backend-config="bucket=<bucket>".
terraform {
  backend "s3" {
    key          = "ticketing-platform/prod/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
