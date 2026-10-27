#!/bin/bash
# Creates the DynamoDB tables of the event catalog, the orders and the scheduler locks. Idempotent: an existing table is left as is.
set -euo pipefail

create_table_if_missing() {
  local table="$1"
  shift
  if awslocal dynamodb describe-table --table-name "${table}" > /dev/null 2>&1; then
    echo "[init] DynamoDB table ${table} already exists"
  else
    awslocal dynamodb create-table --table-name "${table}" --billing-mode PAY_PER_REQUEST "$@" > /dev/null
    echo "[init] DynamoDB table ${table} created"
  fi
}

enable_ttl() {
  awslocal dynamodb update-time-to-live --table-name "$1" \
    --time-to-live-specification "Enabled=true,AttributeName=expiresAtEpochSeconds" > /dev/null 2>&1 || true
}

create_table_if_missing "${DYNAMODB_EVENTS_TABLE}" \
  --attribute-definitions \
    AttributeName=eventId,AttributeType=S \
    AttributeName=status,AttributeType=S \
    AttributeName=date,AttributeType=N \
  --key-schema AttributeName=eventId,KeyType=HASH \
  --global-secondary-indexes \
    "IndexName=${DYNAMODB_EVENTS_BY_STATUS_DATE_INDEX},KeySchema=[{AttributeName=status,KeyType=HASH},{AttributeName=date,KeyType=RANGE}],Projection={ProjectionType=ALL}"

create_table_if_missing "${DYNAMODB_INVENTORY_TABLE}" \
  --attribute-definitions \
    AttributeName=eventId,AttributeType=S \
    AttributeName=shardId,AttributeType=N \
  --key-schema AttributeName=eventId,KeyType=HASH AttributeName=shardId,KeyType=RANGE

create_table_if_missing "${DYNAMODB_ORDERS_TABLE}" \
  --attribute-definitions \
    AttributeName=orderId,AttributeType=S \
    AttributeName=activeBucket,AttributeType=S \
    AttributeName=expiresAt,AttributeType=N \
    AttributeName=publishBucket,AttributeType=S \
    AttributeName=createdAt,AttributeType=N \
  --key-schema AttributeName=orderId,KeyType=HASH \
  --global-secondary-indexes \
    "IndexName=${DYNAMODB_EXPIRING_RESERVATIONS_INDEX},KeySchema=[{AttributeName=activeBucket,KeyType=HASH},{AttributeName=expiresAt,KeyType=RANGE}],Projection={ProjectionType=KEYS_ONLY}" \
    "IndexName=${DYNAMODB_PENDING_PUBLICATION_INDEX},KeySchema=[{AttributeName=publishBucket,KeyType=HASH},{AttributeName=createdAt,KeyType=RANGE}],Projection={ProjectionType=KEYS_ONLY}"

create_table_if_missing "${DYNAMODB_ORDER_AUDIT_TABLE}" \
  --attribute-definitions \
    AttributeName=orderId,AttributeType=S \
    AttributeName=changeId,AttributeType=S \
  --key-schema AttributeName=orderId,KeyType=HASH AttributeName=changeId,KeyType=RANGE

create_table_if_missing "${DYNAMODB_IDEMPOTENCY_TABLE}" \
  --attribute-definitions AttributeName=idempotencyId,AttributeType=S \
  --key-schema AttributeName=idempotencyId,KeyType=HASH
enable_ttl "${DYNAMODB_IDEMPOTENCY_TABLE}"

create_table_if_missing "${DYNAMODB_CUSTOMER_LIMITS_TABLE}" \
  --attribute-definitions AttributeName=limitId,AttributeType=S \
  --key-schema AttributeName=limitId,KeyType=HASH
enable_ttl "${DYNAMODB_CUSTOMER_LIMITS_TABLE}"

create_table_if_missing "${DYNAMODB_SCHEDULER_LOCKS_TABLE}" \
  --attribute-definitions AttributeName=lockName,AttributeType=S \
  --key-schema AttributeName=lockName,KeyType=HASH
