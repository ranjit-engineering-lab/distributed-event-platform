package com.platform.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Order Service — Entry Point
 *
 * Saga Initiator, CQRS Owner, Event Sourcing
 *
 * Port: 8081 (see application.yml)
 */
@SpringBootApplication
@EnableKafka
@ComponentScan(basePackages = {"com.platform.order", "com.platform.shared"})
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
