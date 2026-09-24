$ErrorActionPreference = 'Stop'
$endpoint = 'http://localhost:4566'
$env:AWS_PROFILE = 'floci'
$functionName = 'repaso-order-consumer'
$jar = (Resolve-Path 'target/aws-java-floci-labs-1.0.0-lambda.jar').Path
$queueUrl = aws --endpoint-url $endpoint sqs get-queue-url --queue-name orders --query QueueUrl --output text
$queueArn = aws --endpoint-url $endpoint sqs get-queue-attributes --queue-url $queueUrl --attribute-names QueueArn --query Attributes.QueueArn --output text

# Make this deployment script repeatable by removing mappings from the prior run.
$mappings = aws --endpoint-url $endpoint lambda list-event-source-mappings --function-name $functionName --query 'EventSourceMappings[].UUID' --output text
if ($LASTEXITCODE -eq 0 -and $mappings -and $mappings -ne 'None') {
    foreach ($mapping in ($mappings -split '\s+')) {
        aws --endpoint-url $endpoint lambda delete-event-source-mapping --uuid $mapping | Out-Null
    }
}
$existingFunctions = aws --endpoint-url $endpoint lambda list-functions --query 'Functions[].FunctionName' --output text
if ($LASTEXITCODE -ne 0) { throw 'No se pudieron listar las funciones Lambda de Floci.' }
if (($existingFunctions -split '\s+') -contains $functionName) {
    aws --endpoint-url $endpoint lambda delete-function --function-name $functionName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "No se pudo eliminar la función Lambda $functionName." }
}

aws --endpoint-url $endpoint lambda create-function `
  --function-name $functionName `
  --runtime java21 `
  --role arn:aws:iam::000000000000:role/lambda-role `
  --handler com.jonas.repaso.aws.OrderConsumerHandler::handleRequest `
  --zip-file "fileb://$jar" `
  --timeout 20 `
  --memory-size 512 `
  --environment 'Variables={ORDERS_TABLE=Orders}' `
  --endpoint-url $endpoint | Out-Null

aws --endpoint-url $endpoint lambda create-event-source-mapping `
  --function-name $functionName `
  --event-source-arn $queueArn `
  --batch-size 5 `
  --function-response-types ReportBatchItemFailures `
  --endpoint-url $endpoint | Out-Null

Write-Host "Lambda $functionName creada y conectada a $queueUrl"
