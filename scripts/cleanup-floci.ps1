$ErrorActionPreference = 'Stop'
$endpoint = 'http://localhost:4566'
$env:AWS_PROFILE = 'floci'

$flociDisponible = Test-NetConnection -ComputerName 'localhost' -Port 4566 -InformationLevel Quiet -WarningAction SilentlyContinue
if ($flociDisponible) {
    Write-Host '[1/4] Eliminando los mappings de eventos de Lambda...'
    $mappings = aws --endpoint-url $endpoint lambda list-event-source-mappings --function-name repaso-order-consumer --query 'EventSourceMappings[].UUID' --output text 2>$null
    if ($LASTEXITCODE -eq 0 -and $mappings -and $mappings -ne 'None') {
        foreach ($mapping in ($mappings -split '\s+')) {
            aws --endpoint-url $endpoint lambda delete-event-source-mapping --uuid $mapping 2>$null | Out-Null
        }
    }
    Write-Host '[2/4] Eliminando la Lambda y las colas SQS...'
    aws --endpoint-url $endpoint lambda delete-function --function-name repaso-order-consumer 2>$null

    $queue = aws --endpoint-url $endpoint sqs get-queue-url --queue-name orders --query QueueUrl --output text 2>$null
    if ($LASTEXITCODE -eq 0 -and $queue -and $queue -ne 'None') { aws --endpoint-url $endpoint sqs delete-queue --queue-url $queue 2>$null }
    $dlq = aws --endpoint-url $endpoint sqs get-queue-url --queue-name orders-dlq --query QueueUrl --output text 2>$null
    if ($LASTEXITCODE -eq 0 -and $dlq -and $dlq -ne 'None') { aws --endpoint-url $endpoint sqs delete-queue --queue-url $dlq 2>$null }
    Write-Host '[3/4] Eliminando la tabla DynamoDB y los objetos de S3...'
    aws --endpoint-url $endpoint dynamodb delete-table --table-name Orders 2>$null
    aws --endpoint-url $endpoint s3 rm s3://repaso-orders --recursive 2>$null
    aws --endpoint-url $endpoint s3api delete-bucket --bucket repaso-orders 2>$null
} else {
    Write-Host 'Floci no esta activo en localhost:4566; se omite la limpieza de recursos AWS.'
}
Write-Host '[4/4] Deteniendo los contenedores de Floci...'
docker compose -f compose.yaml -f compose.lambda.yaml down
Write-Host 'Recursos eliminados y Floci detenido.'
