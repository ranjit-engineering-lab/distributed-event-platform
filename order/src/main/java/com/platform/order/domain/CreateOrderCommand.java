package com.platform.order.domain;

import com.platform.shared.events.Events.ShippingAddress;

import java.util.List;

import com.platform.shared.events.Events.OrderItem;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Create Order Command — validated input DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderCommand {
    private String customerId;
    private List<OrderItem> items;
    private String paymentMethod;
    private ShippingAddress shippingAddress;
    private String correlationId; // Optional: provided by caller for idempotency
}