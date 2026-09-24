package com.jonas.repaso.aws;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Crea los clientes de AWS que usa la API y decide si apuntan a Floci o a AWS. */
@Configuration
public class AwsClientsConfiguration {
    /** Usa rutas de S3 con el bucket en la URL cuando hay un endpoint local. */
    @Bean S3Client s3Client(AwsProperties properties) {
        var builder = S3Client.builder().region(Region.of(properties.region()));
        configure(builder, properties);
        if (!properties.endpoint().isBlank()) builder.forcePathStyle(true);
        return builder.build();
    }

    /** Crea el cliente que publica los pedidos en la cola SQS. */
    @Bean SqsClient sqsClient(AwsProperties properties) {
        var builder = SqsClient.builder().region(Region.of(properties.region()));
        configure(builder, properties);
        return builder.build();
    }

    /** Crea el cliente de DynamoDB con la misma configuración de región y endpoint. */
    @Bean DynamoDbClient dynamoDbClient(AwsProperties properties) {
        var builder = DynamoDbClient.builder().region(Region.of(properties.region()));
        configure(builder, properties);
        return builder.build();
    }

    /** Usa credenciales ficticias con Floci y la cadena habitual de credenciales con AWS. */
    private static void configure(software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<?, ?> builder,
                                  AwsProperties properties) {
        if (properties.endpoint().isBlank()) {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        } else {
            builder.endpointOverride(URI.create(properties.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        }
    }
}
