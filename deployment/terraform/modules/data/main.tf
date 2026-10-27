# Customer managed key of the tables, rotated every year. The tables are in tables.tf.

data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "key" {
  # checkov:skip=CKV_AWS_109: in a key policy "*" is this key only; the account root delegates its use to IAM policies
  # checkov:skip=CKV_AWS_111: in a key policy "*" is this key only; the account root delegates its use to IAM policies
  # checkov:skip=CKV_AWS_356: in a key policy "*" is this key only; the account root delegates its use to IAM policies
  statement {
    sid       = "AccountAdministration"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }
}

resource "aws_kms_key" "tables" {
  description             = "Encryption of the DynamoDB tables of ${var.name}"
  enable_key_rotation     = true
  deletion_window_in_days = var.kms_deletion_window_days
  policy                  = data.aws_iam_policy_document.key.json
}

resource "aws_kms_alias" "tables" {
  name          = "alias/${var.name}-dynamodb"
  target_key_id = aws_kms_key.tables.key_id
}
