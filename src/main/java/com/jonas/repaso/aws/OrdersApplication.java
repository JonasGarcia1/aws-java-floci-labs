package com.jonas.repaso.aws;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Inicia la API de pedidos y habilita la lectura de las propiedades de AWS. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrdersApplication {
    /** Arranca el servidor HTTP de Spring Boot. */
    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }
}
