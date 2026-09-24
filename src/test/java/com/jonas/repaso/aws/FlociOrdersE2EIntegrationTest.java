package com.jonas.repaso.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateEventSourceMappingRequest;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteEventSourceMappingRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.FunctionResponseType;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Prueba el flujo completo contra Floci de Compose al activar {@code -Dfloci.e2e=true}. */
class FlociOrdersE2EIntegrationTest {
    private static final String ENDPOINT = "http://localhost:4566";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Publica un pedido y su duplicado, comprueba DynamoDB y espera que un
     * mensaje inválido llegue a la DLQ. El bloque final limpia los recursos.
     */
    @Test
    void apiSqsLambdaDynamoAndDlqWorkTogether() throws Exception {
        assumeTrue(Boolean.getBoolean("floci.e2e"), "Set -Dfloci.e2e=true to run against the Compose Floci instance");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String bucket = "repaso-e2e-" + suffix;
        String queueName = "repaso-orders-" + suffix;
        String dlqName = queueName + "-dlq";
        String table = "Orders" + suffix;
        String function = "repaso-order-consumer-" + suffix;
        String mappingId = null;
        boolean functionCreated = false;
        String orderObjectKey = null;
        String queueUrl = null;
        String dlqUrl = null;
        ConfigurableApplicationContext api = null;

        try (var s3 = s3Client(); var sqs = sqsClient(); var dynamo = dynamoClient(); var lambda = lambdaClient()) {
            try {
                s3.createBucket(request -> request.bucket(bucket));
            } catch (BucketAlreadyExistsException collision) {
                throw new IllegalStateException("Generated bucket name unexpectedly collided: " + bucket, collision);
            }
            dlqUrl = sqs.createQueue(CreateQueueRequest.builder().queueName(dlqName).build()).queueUrl();
            String dlqArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(dlqUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN).build()).attributes().get(QueueAttributeName.QUEUE_ARN);
            String redrivePolicy = mapper.writeValueAsString(Map.of("deadLetterTargetArn", dlqArn, "maxReceiveCount", "3"));
            queueUrl = sqs.createQueue(CreateQueueRequest.builder().queueName(queueName).attributes(Map.of(
                    QueueAttributeName.REDRIVE_POLICY, redrivePolicy)).build()).queueUrl();
            String queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN).build()).attributes().get(QueueAttributeName.QUEUE_ARN);
            dynamo.createTable(request -> request.tableName(table)
                    .attributeDefinitions(AttributeDefinition.builder().attributeName("orderId").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName("orderId").keyType(KeyType.HASH).build())
                    .billingMode(BillingMode.PAY_PER_REQUEST));

            byte[] lambdaJar = Files.readAllBytes(Path.of("target/aws-java-floci-labs-1.0.0-lambda.jar"));
            lambda.createFunction(CreateFunctionRequest.builder().functionName(function).runtime(Runtime.JAVA21)
                    .role(ROLE).handler("com.jonas.repaso.aws.OrderConsumerHandler::handleRequest")
                    .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(lambdaJar)).build())
                    .timeout(20).memorySize(512).environment(environment -> environment.variables(Map.of("ORDERS_TABLE", table)))
                    .build());
            functionCreated = true;
            mappingId = lambda.createEventSourceMapping(CreateEventSourceMappingRequest.builder()
                    .functionName(function).eventSourceArn(queueArn).batchSize(5)
                    .functionResponseTypes(FunctionResponseType.REPORT_BATCH_ITEM_FAILURES).build()).uuid();

            api = new SpringApplicationBuilder(OrdersApplication.class).run("--spring.main.banner-mode=off",
                    "--server.port=0", "--aws.region=us-east-1", "--aws.endpoint=" + ENDPOINT,
                    "--aws.orders-bucket=" + bucket, "--aws.orders-queue-url=" + queueUrl,
                    "--aws.orders-table=" + table);
            int port = ((WebServerApplicationContext) api).getWebServer().getPort();
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String orderId = "order-" + suffix;
            String orderKey = orderId + ".json";
            orderObjectKey = orderKey;
            String orderJson = mapper.writeValueAsString(new OrderRequest(orderId, "customer-e2e", new BigDecimal("42.50")));
            for (int duplicate = 0; duplicate < 2; duplicate++) {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
                        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(orderJson)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(202, response.statusCode(), response.body());
            }
            assertNotNull(s3.headObject(request -> request.bucket(bucket).key(orderKey)));
            await("order processed by Lambda", Duration.ofSeconds(60), () -> {
                var item = dynamo.getItem(request -> request.tableName(table)
                        .key(Map.of("orderId", AttributeValue.fromS(orderId)))).item();
                return "processed".equals(item.getOrDefault("status", AttributeValue.fromS("")).s());
            });
            String sourceQueueUrl = queueUrl;
            await("original and duplicate messages acknowledged", Duration.ofSeconds(45), () -> {
                var counts = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(sourceQueueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE).build()).attributes();
                return "0".equals(counts.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                        && "0".equals(counts.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
            });

            sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody("not-json").build());
            String deadLetterQueueUrl = dlqUrl;
            await("invalid message moved to DLQ", Duration.ofSeconds(180), () -> !sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(deadLetterQueueUrl).maxNumberOfMessages(1).waitTimeSeconds(1).build()).messages().isEmpty());
        } finally {
            if (api != null) api.close();
            try (var lambda = lambdaClient(); var sqs = sqsClient(); var dynamo = dynamoClient(); var s3 = s3Client()) {
                if (mappingId != null) lambda.deleteEventSourceMapping(DeleteEventSourceMappingRequest.builder().uuid(mappingId).build());
                if (mappingId != null) Thread.sleep(1500);
                if (functionCreated) lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(function).build());
                if (queueUrl != null) sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
                if (dlqUrl != null) sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(dlqUrl).build());
                try { dynamo.deleteTable(request -> request.tableName(table)); } catch (RuntimeException ignored) { }
                String cleanupObjectKey = orderObjectKey;
                if (cleanupObjectKey != null) {
                    try { s3.deleteObject(request -> request.bucket(bucket).key(cleanupObjectKey)); } catch (RuntimeException ignored) { }
                }
                try { s3.deleteBucket(request -> request.bucket(bucket)); } catch (RuntimeException ignored) { }
            }
        }
    }

    /** Repite una comprobación hasta que se cumpla o venza el tiempo de espera. */
    private static void await(String description, Duration timeout, CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) return;
            Thread.sleep(1000);
        }
        assertTrue(false, "Timed out waiting for: " + description);
    }

    /** Construye un cliente S3 dirigido al emulador y con rutas compatibles. */
    private S3Client s3Client() {
        return S3Client.builder().endpointOverride(URI.create(ENDPOINT)).region(Region.US_EAST_1)
                .credentialsProvider(credentials()).forcePathStyle(true).build();
    }

    /** Construye un cliente SQS dirigido al emulador. */
    private SqsClient sqsClient() {
        return SqsClient.builder().endpointOverride(URI.create(ENDPOINT)).region(Region.US_EAST_1)
                .credentialsProvider(credentials()).build();
    }

    /** Construye un cliente DynamoDB dirigido al emulador. */
    private DynamoDbClient dynamoClient() {
        return DynamoDbClient.builder().endpointOverride(URI.create(ENDPOINT)).region(Region.US_EAST_1)
                .credentialsProvider(credentials()).build();
    }

    /** Construye un cliente Lambda dirigido al emulador. */
    private LambdaClient lambdaClient() {
        return LambdaClient.builder().endpointOverride(URI.create(ENDPOINT)).region(Region.US_EAST_1)
                .credentialsProvider(credentials()).build();
    }

    /** Usa valores ficticios para autenticar las peticiones locales. */
    private static StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    }

    /** Permite esperar condiciones que pueden fallar al consultar los servicios. */
    @FunctionalInterface
    private interface CheckedCondition { boolean evaluate() throws Exception; }
}
