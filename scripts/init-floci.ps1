$ErrorActionPreference = 'Stop'
$endpoint = 'http://localhost:4566'
$env:AWS_PROFILE = 'floci'
$dlqUrl = aws --endpoint-url $endpoint sqs create-queue --queue-name orders-dlq --query QueueUrl --output text
$dlqArn = aws --endpoint-url $endpoint sqs get-queue-attributes --queue-url $dlqUrl --attribute-names QueueArn --query Attributes.QueueArn --output text
$redrivePolicy = "{`"deadLetterTargetArn`":`"$dlqArn`",`"maxReceiveCount`":`"3`"}"
$attributesFile = Join-Path $env:TEMP 'repaso-orders-queue-attributes.json'
@{ RedrivePolicy = $redrivePolicy } | ConvertTo-Json -Compress | Set-Content -Path $attributesFile -Encoding ascii

aws --endpoint-url $endpoint s3api create-bucket --bucket repaso-orders 2>$null
if ($LASTEXITCODE -ne 0) { Write-Host 'El bucket ya existe o no pudo crearse; revisá el mensaje de AWS CLI.' }
$queueUrl = aws --endpoint-url $endpoint sqs create-queue --queue-name orders --attributes "file://$attributesFile" --query QueueUrl --output text
aws --endpoint-url $endpoint sqs set-queue-attributes --queue-url $queueUrl --attributes "file://$attributesFile"
aws --endpoint-url $endpoint dynamodb create-table --table-name Orders --attribute-definitions AttributeName=orderId,AttributeType=S --key-schema AttributeName=orderId,KeyType=HASH --billing-mode PAY_PER_REQUEST 2>$null
if ($LASTEXITCODE -ne 0) { Write-Host 'La tabla ya existe o no pudo crearse; revisá el mensaje de AWS CLI.' }
Write-Host "ORDERS_QUEUE_URL=$queueUrl"
Write-Host "ORDERS_DLQ_URL=$dlqUrl"
Write-Host 'Recursos listos: bucket, SQS con DLQ tras 3 recepciones y tabla.'
Remove-Item -LiteralPath $attributesFile -Force
