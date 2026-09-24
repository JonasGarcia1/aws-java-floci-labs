package com.jonas.repaso.aws;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Expone el ingreso de pedidos por HTTP y delega su publicación al servicio. */
@RestController
@RequestMapping("/orders")
public class OrdersController {
    private final OrderService orders;

    /** Recibe el servicio encargado de guardar y publicar cada pedido. */
    public OrdersController(OrderService orders) { this.orders = orders; }

    /** Devuelve 202 cuando S3 y SQS aceptaron el pedido; Lambda lo procesa después. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OrderRequest create(@RequestBody OrderRequest request) { return orders.publish(request); }
}
