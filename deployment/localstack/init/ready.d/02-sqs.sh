#!/bin/bash
# Creates the queues of the payment notices and of the accepted orders, each with its dead letter queue.
# Idempotent: an existing queue is left as is. The queue of accepted orders is the last one, because the
# integration tests wait for its line.
set -euo pipefail

readonly MESSAGE_WAIT_SECONDS=20
readonly QUEUE_RETENTION_SECONDS=345600
readonly DLQ_RETENTION_SECONDS=1209600

queue_url() {
  awslocal sqs get-queue-url --queue-name "$1" --query QueueUrl --output text 2> /dev/null
}

create_queue_if_missing() {
  local queue="$1"
  local attributes="$2"
  if queue_url "${queue}" > /dev/null; then
    echo "[init] SQS queue ${queue} already exists"
  else
    awslocal sqs create-queue --queue-name "${queue}" --attributes "${attributes}" > /dev/null
    echo "[init] SQS queue ${queue} created"
  fi
}

create_queue_with_dead_letter() {
  local queue="$1"
  local dead_letter_queue="$2"
  create_queue_if_missing "${dead_letter_queue}" "{\"MessageRetentionPeriod\":\"${DLQ_RETENTION_SECONDS}\"}"
  local dlq_arn
  dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$(queue_url "${dead_letter_queue}")" \
    --attribute-names QueueArn --query Attributes.QueueArn --output text)
  local redrive_policy="{\\\"deadLetterTargetArn\\\":\\\"${dlq_arn}\\\",\\\"maxReceiveCount\\\":\\\"${SQS_MAX_RECEIVE_COUNT}\\\"}"
  create_queue_if_missing "${queue}" "{\"VisibilityTimeout\":\"${SQS_VISIBILITY_TIMEOUT_SECONDS}\",\
\"ReceiveMessageWaitTimeSeconds\":\"${MESSAGE_WAIT_SECONDS}\",\"MessageRetentionPeriod\":\"${QUEUE_RETENTION_SECONDS}\",\
\"RedrivePolicy\":\"${redrive_policy}\"}"
}

create_queue_with_dead_letter "${SQS_PAYMENT_CONFIRMED_QUEUE}" "${SQS_PAYMENT_CONFIRMED_DLQ}"
create_queue_with_dead_letter "${SQS_ORDER_PLACED_QUEUE}" "${SQS_ORDER_PLACED_DLQ}"
