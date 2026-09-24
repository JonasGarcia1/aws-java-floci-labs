package com.jonas.repaso.aws;

import java.math.BigDecimal;

/** Define los datos del pedido compartidos por la API y el consumidor de SQS. */
public record OrderRequest(String orderId, String customerId, BigDecimal total) {
}
