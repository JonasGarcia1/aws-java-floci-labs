$ErrorActionPreference = 'Stop'

Write-Host '[1/2] Compilando la aplicacion y preparando el paquete de Lambda...'
mvn clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'No se pudo compilar el laboratorio.' }

Write-Host '[2/2] Ejecutando la prueba integral contra Floci...'
mvn '-Dfloci.e2e=true' '-Dtest=FlociOrdersE2EIntegrationTest' '-Dsurefire.useFile=false' test
if ($LASTEXITCODE -ne 0) { throw 'Fallo la integracion API -> S3/SQS -> Lambda -> DynamoDB/DLQ.' }

Write-Host 'OK: API -> S3/SQS -> Lambda -> DynamoDB; duplicado reconocido y evento invalido en DLQ.'
