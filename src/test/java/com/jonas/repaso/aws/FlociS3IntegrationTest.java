package com.jonas.repaso.aws;

import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifica operaciones S3 reales contra un contenedor aislado de Floci. */
@Testcontainers(disabledWithoutDocker = true)
class FlociS3IntegrationTest {
    @Container
    static final FlociContainer floci = new FlociContainer("floci/floci:2.1.0");

    /** Crea un bucket, sube y lista un objeto, y elimina los recursos de prueba. */
    @Test
    void uploadsAndListsObjectUsingTheLocalEndpoint() {
        String bucket = "repaso-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(floci.getEndpoint()))
                .region(Region.of(floci.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true)
                .build()) {
            s3.createBucket(request -> request.bucket(bucket));
            s3.putObject(request -> request.bucket(bucket).key("hello.txt"),
                    software.amazon.awssdk.core.sync.RequestBody.fromString("hola floci"));
            assertTrue(s3.listObjectsV2(request -> request.bucket(bucket)).contents()
                    .stream().anyMatch(object -> object.key().equals("hello.txt")));
            s3.deleteObject(request -> request.bucket(bucket).key("hello.txt"));
            s3.deleteBucket(request -> request.bucket(bucket));
        }
    }
}
