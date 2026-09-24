#!/usr/bin/env bash
set -euo pipefail

export AWS_PROFILE=floci
endpoint='http://localhost:4566'
bucket='repaso-orders'
queue='orders'
dlq='orders-dlq'

printf '[1/4] Creando o verificando el bucket S3...\n'
if ! aws --endpoint-url "$endpoint" s3api create-bucket --bucket "$bucket" >/dev/null; then
  printf 'El bucket ya existe o no pudo crearse.\n' >&2
fi
printf '[2/4] Creando o verificando la cola de mensajes fallidos...\n'
dlq_url=$(aws --endpoint-url "$endpoint" sqs create-queue --queue-name "$dlq" --query QueueUrl --output text)
dlq_arn=$(aws --endpoint-url "$endpoint" sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn --query Attributes.QueueArn --output text)
attributes_file=$(mktemp)
trap 'rm -f "$attributes_file"' EXIT
printf '{"RedrivePolicy":"{\\"deadLetterTargetArn\\":\\"%s\\",\\"maxReceiveCount\\":\\"3\\"}"}\n' "$dlq_arn" > "$attributes_file"
printf '[3/4] Configurando la cola orders y su politica de reintentos...\n'
queue_url=$(aws --endpoint-url "$endpoint" sqs create-queue --queue-name "$queue" --attributes "file://$attributes_file" --query QueueUrl --output text)
aws --endpoint-url "$endpoint" sqs set-queue-attributes --queue-url "$queue_url" --attributes "file://$attributes_file"
printf '[4/4] Creando o verificando la tabla DynamoDB...\n'
aws --endpoint-url "$endpoint" dynamodb create-table \
  --table-name Orders \
  --attribute-definitions AttributeName=orderId,AttributeType=S \
  --key-schema AttributeName=orderId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST >/dev/null || printf 'La tabla ya existe o no pudo crearse.\n' >&2
printf 'ORDERS_QUEUE_URL=%s\n' "$queue_url"
printf 'ORDERS_DLQ_URL=%s\n' "$dlq_url"
printf 'Recursos listos: bucket, SQS con DLQ tras 3 recepciones y tabla.\n'
