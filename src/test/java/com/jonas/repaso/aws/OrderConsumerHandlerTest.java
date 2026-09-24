package com.jonas.repaso.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import org.junit.jupiter.api.Test;
import java.util.List;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Comprueba las respuestas parciales del consumidor ante errores y duplicados. */
class OrderConsumerHandlerTest {
    /** Un mensaje que no es JSON debe volver a SQS para su reintento. */
    @Test
    void malformedMessageIsReturnedForRetry() {
        var record = new SQSEvent.SQSMessage();
        record.setMessageId("message-1");
        record.setBody("not-json");
        var event = new SQSEvent();
        event.setRecords(List.of(record));
        var response = new OrderConsumerHandler().handleRequest(event, context());
        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("message-1", response.getBatchItemFailures().getFirst().getItemIdentifier());
    }

    /** Solo el mensaje inválido del lote debe aparecer entre los fallidos. */
    @Test
    void mixedBatchOnlyReturnsInvalidMessageForRetry() {
        var valid = message("valid-1", "{\"orderId\":\"order-1\",\"customerId\":\"customer-1\",\"total\":12.5}");
        var invalid = message("invalid-1", "not-json");
        var event = new SQSEvent();
        event.setRecords(List.of(valid, invalid));
        var dynamo = mock(DynamoDbClient.class);
        var handler = new OrderConsumerHandler() {
            @Override protected DynamoDbClient createDynamoDbClient() { return dynamo; }
        };

        var response = handler.handleRequest(event, context());

        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("invalid-1", response.getBatchItemFailures().getFirst().getItemIdentifier());
        verify(dynamo).putItem(any(java.util.function.Consumer.class));
    }

    /** Un pedido ya presente en DynamoDB debe darse por procesado. */
    @Test
    void duplicateConditionalWriteIsAcknowledged() {
        var event = new SQSEvent();
        event.setRecords(List.of(message("duplicate-1", "{\"orderId\":\"order-1\",\"customerId\":\"customer-1\",\"total\":12.5}")));
        var dynamo = mock(DynamoDbClient.class);
        doThrow(ConditionalCheckFailedException.builder().message("already exists").build())
                .when(dynamo).putItem(any(java.util.function.Consumer.class));
        var handler = new OrderConsumerHandler() {
            @Override protected DynamoDbClient createDynamoDbClient() { return dynamo; }
        };

        var response = handler.handleRequest(event, context());

        assertEquals(0, response.getBatchItemFailures().size());
    }

    /** Crea un mensaje de SQS con identificador y contenido para las pruebas. */
    private static SQSEvent.SQSMessage message(String id, String body) {
        var record = new SQSEvent.SQSMessage();
        record.setMessageId(id);
        record.setBody(body);
        return record;
    }

    /** Proporciona el contexto mínimo que requiere el handler durante la prueba. */
    private static Context context() {
        return new Context() {
            @Override public String getAwsRequestId() { return "test"; }
            @Override public String getLogGroupName() { return "test"; }
            @Override public String getLogStreamName() { return "test"; }
            @Override public String getFunctionName() { return "test"; }
            @Override public String getFunctionVersion() { return "test"; }
            @Override public String getInvokedFunctionArn() { return "test"; }
            @Override public com.amazonaws.services.lambda.runtime.CognitoIdentity getIdentity() { return null; }
            @Override public com.amazonaws.services.lambda.runtime.ClientContext getClientContext() { return null; }
            @Override public int getRemainingTimeInMillis() { return 1000; }
            @Override public int getMemoryLimitInMB() { return 128; }
            @Override public com.amazonaws.services.lambda.runtime.LambdaLogger getLogger() {
                return new com.amazonaws.services.lambda.runtime.LambdaLogger() {
                    @Override public void log(String message) {}
                    @Override public void log(byte[] message) {}
                };
            }
        };
    }
}
