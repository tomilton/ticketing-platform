# The seven tables of the application, with the same keys, indexes and TTL that
# deployment/localstack/init/ready.d/01-dynamodb.sh creates locally. On-demand capacity, point-in-time
# recovery and encryption with a customer managed key.

locals {
  ttl_attribute = "expiresAtEpochSeconds"

  tables = {
    events = {
      hash_key   = "eventId"
      range_key  = null
      attributes = { eventId = "S", status = "S", date = "N" }
      indexes = [{
        name       = var.index_names.events_by_status_date
        hash_key   = "status"
        range_key  = "date"
        projection = "ALL"
      }]
      ttl = false
    }
    inventory = {
      hash_key   = "eventId"
      range_key  = "shardId"
      attributes = { eventId = "S", shardId = "N" }
      indexes    = []
      ttl        = false
    }
    orders = {
      hash_key   = "orderId"
      range_key  = null
      attributes = { orderId = "S", activeBucket = "S", expiresAt = "N", publishBucket = "S", createdAt = "N" }
      indexes = [
        {
          name       = var.index_names.expiring_reservations
          hash_key   = "activeBucket"
          range_key  = "expiresAt"
          projection = "KEYS_ONLY"
        },
        {
          name       = var.index_names.pending_publication
          hash_key   = "publishBucket"
          range_key  = "createdAt"
          projection = "KEYS_ONLY"
        },
      ]
      ttl = false
    }
    order_audit = {
      hash_key   = "orderId"
      range_key  = "changeId"
      attributes = { orderId = "S", changeId = "S" }
      indexes    = []
      ttl        = false
    }
    idempotency = {
      hash_key   = "idempotencyId"
      range_key  = null
      attributes = { idempotencyId = "S" }
      indexes    = []
      ttl        = true
    }
    customer_limits = {
      hash_key   = "limitId"
      range_key  = null
      attributes = { limitId = "S" }
      indexes    = []
      ttl        = true
    }
    scheduler_locks = {
      hash_key   = "lockName"
      range_key  = null
      attributes = { lockName = "S" }
      indexes    = []
      ttl        = false
    }
  }
}

resource "aws_dynamodb_table" "this" {
  for_each = local.tables

  name                        = var.table_names[each.key]
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = each.value.hash_key
  range_key                   = each.value.range_key
  deletion_protection_enabled = var.deletion_protection

  dynamic "attribute" {
    for_each = each.value.attributes

    content {
      name = attribute.key
      type = attribute.value
    }
  }

  dynamic "global_secondary_index" {
    for_each = each.value.indexes

    content {
      name            = global_secondary_index.value.name
      projection_type = global_secondary_index.value.projection

      key_schema {
        attribute_name = global_secondary_index.value.hash_key
        key_type       = "HASH"
      }

      key_schema {
        attribute_name = global_secondary_index.value.range_key
        key_type       = "RANGE"
      }
    }
  }

  dynamic "ttl" {
    for_each = each.value.ttl ? [local.ttl_attribute] : []

    content {
      enabled        = true
      attribute_name = ttl.value
    }
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.tables.arn
  }
}
