mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }

  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }

  mock_resource "aws_kms_key" {
    defaults = {
      arn = "arn:aws:kms:us-east-1:123456789012:key/mock"
    }
  }
}

variables {
  name = "ticketing-test"
  table_names = {
    events          = "events"
    inventory       = "inventory"
    orders          = "orders"
    order_audit     = "order_audit"
    idempotency     = "idempotency"
    customer_limits = "customer_event_limits"
    scheduler_locks = "scheduler_locks"
  }
  index_names = {
    events_by_status_date = "by-status-date"
    expiring_reservations = "expiring-reservations"
    pending_publication   = "pending-publication"
  }
  deletion_protection      = false
  kms_deletion_window_days = 7
}

run "creates_the_seven_tables_of_the_application" {
  command = plan

  assert {
    condition = toset([for table in aws_dynamodb_table.this : table.name]) == toset([
      "events", "inventory", "orders", "order_audit", "idempotency", "customer_event_limits", "scheduler_locks"
    ])
    error_message = "The seven tables must use the names the application reads."
  }

  assert {
    condition     = alltrue([for table in aws_dynamodb_table.this : table.billing_mode == "PAY_PER_REQUEST"])
    error_message = "Every table must be on demand."
  }
}

run "keeps_the_keys_and_indexes_of_the_local_environment" {
  command = plan

  assert {
    condition     = aws_dynamodb_table.this["inventory"].hash_key == "eventId" && aws_dynamodb_table.this["inventory"].range_key == "shardId"
    error_message = "The inventory is keyed by event and shard."
  }

  assert {
    condition     = toset([for index in aws_dynamodb_table.this["orders"].global_secondary_index : index.name]) == toset(["expiring-reservations", "pending-publication"])
    error_message = "The orders table needs the expiring reservations and pending publication indexes."
  }

  assert {
    condition     = alltrue([for index in aws_dynamodb_table.this["orders"].global_secondary_index : index.projection_type == "KEYS_ONLY"])
    error_message = "The indexes of the orders table project only the keys."
  }

  assert {
    condition     = one(aws_dynamodb_table.this["events"].global_secondary_index).name == "by-status-date"
    error_message = "The events table needs the index by status and date."
  }

  assert {
    condition = [for key in one(aws_dynamodb_table.this["events"].global_secondary_index).key_schema : key.attribute_name] == [
      "status", "date"
    ]
    error_message = "The index of the events table is keyed by status and date."
  }
}

run "expires_idempotency_keys_and_customer_limits_by_ttl" {
  command = plan

  assert {
    condition     = one(aws_dynamodb_table.this["idempotency"].ttl).attribute_name == "expiresAtEpochSeconds"
    error_message = "The idempotency table expires its items by TTL."
  }

  assert {
    condition     = one(aws_dynamodb_table.this["customer_limits"].ttl).attribute_name == "expiresAtEpochSeconds"
    error_message = "The customer limits table expires its items by TTL."
  }

  assert {
    condition     = length(aws_dynamodb_table.this["orders"].ttl) == 0
    error_message = "Orders are never deleted by TTL."
  }
}

run "encrypts_with_a_rotated_key_and_enables_recovery" {
  command = plan

  assert {
    condition     = alltrue([for table in aws_dynamodb_table.this : table.point_in_time_recovery[0].enabled])
    error_message = "Every table must have point-in-time recovery."
  }

  assert {
    condition     = alltrue([for table in aws_dynamodb_table.this : table.server_side_encryption[0].enabled])
    error_message = "Every table must be encrypted with the customer managed key."
  }

  assert {
    condition     = aws_kms_key.tables.enable_key_rotation
    error_message = "The key must rotate."
  }

  assert {
    condition     = alltrue([for table in aws_dynamodb_table.this : table.deletion_protection_enabled == false])
    error_message = "Outside production the tables can be deleted."
  }
}

run "protects_the_tables_against_deletion_in_production" {
  command = plan

  variables {
    deletion_protection = true
  }

  assert {
    condition     = alltrue([for table in aws_dynamodb_table.this : table.deletion_protection_enabled])
    error_message = "In production every table must be protected against deletion."
  }
}
