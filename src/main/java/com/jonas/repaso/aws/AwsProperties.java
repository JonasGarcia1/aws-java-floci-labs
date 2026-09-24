package com.jonas.repaso.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agrupa la región, el endpoint y los nombres de recursos configurados bajo {@code aws}. */
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(String region, String endpoint, String ordersBucket,
                            String ordersQueueUrl, String ordersTable) {
}
