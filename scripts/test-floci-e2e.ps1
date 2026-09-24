$ErrorActionPreference = 'Stop'

mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'No se pudo compilar el laboratorio.' }

mvn -q -Dfloci.e2e=true -Dtest=FlociOrdersE2EIntegrationTest test
if ($LASTEXITCODE -ne 0) { throw 'Falló la integración API → S3/SQS → Lambda → DynamoDB/DLQ.' }

Write-Host 'OK: API → S3/SQS → Lambda → DynamoDB; duplicado reconocido y evento inválido en DLQ.'
