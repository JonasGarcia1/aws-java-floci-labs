$ErrorActionPreference = 'Stop'
$endpoint = 'http://localhost:4566'
$env:AWS_PROFILE = 'floci'

$mappings = aws --endpoint-url $endpoint lambda list-event-source-mappings --function-name repaso-order-consumer --query 'EventSourceMappings[].UUID' --output text
if ($LASTEXITCODE -eq 0 -and $mappings -and $mappings -ne 'None') {
    foreach ($mapping in ($mappings -split '\s+')) {
        aws --endpoint-url $endpoint lambda delete-event-source-mapping --uuid $mapping | Out-Null
    }
}
aws --endpoint-url $endpoint lambda delete-function --function-name repaso-order-consumer 2>$null

$queue = aws --endpoint-url $endpoint sqs get-queue-url --queue-name orders --query QueueUrl --output text 2>$null
if ($LASTEXITCODE -eq 0 -and $queue -and $queue -ne 'None') { aws --endpoint-url $endpoint sqs delete-queue --queue-url $queue }
$dlq = aws --endpoint-url $endpoint sqs get-queue-url --queue-name orders-dlq --query QueueUrl --output text 2>$null
if ($LASTEXITCODE -eq 0 -and $dlq -and $dlq -ne 'None') { aws --endpoint-url $endpoint sqs delete-queue --queue-url $dlq }
aws --endpoint-url $endpoint dynamodb delete-table --table-name Orders 2>$null
aws --endpoint-url $endpoint s3 rm s3://repaso-orders --recursive 2>$null
aws --endpoint-url $endpoint s3api delete-bucket --bucket repaso-orders 2>$null
docker compose -f compose.yaml -f compose.lambda.yaml down
Write-Host 'Recursos eliminados y Floci detenido.'
