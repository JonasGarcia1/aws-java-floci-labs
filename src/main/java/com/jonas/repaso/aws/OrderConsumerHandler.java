package com.jonas.repaso.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** Consume lotes de SQS, registra pedidos en DynamoDB e informa los mensajes fallidos. */
public class OrderConsumerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private final ObjectMapper mapper = new ObjectMapper();

    /** Procesa cada mensaje por separado para reintentar solo los que fallaron. */
    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        try (var dynamo = createDynamoDbClient()) {
            var failures = new ArrayList<SQSBatchResponse.BatchItemFailure>();
            String table = System.getenv().getOrDefault("ORDERS_TABLE", "Orders");
            for (var message : event.getRecords()) {
                try {
                    OrderRequest order = mapper.readValue(message.getBody(), OrderRequest.class);
                    // La condición evita sobrescribir un pedido procesado en una entrega anterior.
                    dynamo.putItem(request -> request.tableName(table).item(java.util.Map.of(
                            "orderId", AttributeValue.fromS(order.orderId()),
                            "customerId", AttributeValue.fromS(order.customerId()),
                            "total", AttributeValue.fromN(order.total().toPlainString()),
                            "status", AttributeValue.fromS("processed")))
                            .conditionExpression("attribute_not_exists(orderId)"));
                } catch (ConditionalCheckFailedException duplicate) {
                    // Un pedido ya registrado se considera completo y no vuelve a la cola.
                } catch (Exception error) {
                    failures.add(SQSBatchResponse.BatchItemFailure.builder().withItemIdentifier(message.getMessageId()).build());
                    context.getLogger().log("Failed to process SQS message " + message.getMessageId() + ": " + error.getMessage());
                }
            }
            return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
        }
    }

    /** Usa Floci con credenciales ficticias si hay endpoint local; si no, usa AWS. */
    protected DynamoDbClient createDynamoDbClient() {
        String endpoint = System.getenv().getOrDefault("AWS_ENDPOINT_URL", "");
        var builder = DynamoDbClient.builder().region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")));
        if (endpoint.isBlank()) builder.credentialsProvider(DefaultCredentialsProvider.create());
        else builder.endpointOverride(URI.create(endpoint)).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        return builder.build();
    }
}
