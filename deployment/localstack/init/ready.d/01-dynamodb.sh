#!/bin/bash
# Creates the DynamoDB tables of the event catalog. Idempotent: an existing table is left as is.
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
