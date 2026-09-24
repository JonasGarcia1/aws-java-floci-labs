package com.jonas.repaso.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Coordina el guardado del pedido en S3 y su publicación posterior en SQS. */
@Service
public class OrderService {
    private final S3Client s3;
    private final SqsClient sqs;
    private final AwsProperties properties;
    private final ObjectMapper mapper;

    /** Recibe los clientes de AWS, los nombres de recursos y el serializador JSON. */
    public OrderService(S3Client s3, SqsClient sqs, AwsProperties properties, ObjectMapper mapper) {
        this.s3 = s3;
        this.sqs = sqs;
        this.properties = properties;
        this.mapper = mapper;
    }

    /**
     * Guarda el JSON en S3 antes de enviarlo a SQS. Si SQS falla, el objeto puede
     * permanecer en S3 porque ambas operaciones no forman una transacción.
     */
    public OrderRequest publish(OrderRequest order) {
        if (order.orderId() == null || order.orderId().isBlank() || order.customerId() == null || order.total() == null) {
            throw new IllegalArgumentException("orderId, customerId y total son obligatorios");
        }
        try {
            String json = mapper.writeValueAsString(order);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            s3.putObject(PutObjectRequest.builder().bucket(properties.ordersBucket()).key(order.orderId() + ".json").contentType("application/json").build(), RequestBody.fromBytes(bytes));
            sqs.sendMessage(SendMessageRequest.builder().queueUrl(properties.ordersQueueUrl()).messageBody(json).build());
            return order;
        } catch (Exception error) {
            throw new IllegalStateException("No se pudo guardar y publicar el pedido", error);
        }
    }
}
