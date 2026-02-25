package com.platform.order.api;

import com.platform.order.domain.*;
import com.platform.order.service.OrderEventStore;
import com.platform.shared.events.Events.*;
import com.platform.shared.saga.SagaStateStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Order REST Controller
 *
 * CQRS: Write and Read endpoints are explicitly separated.
 *
 * Write endpoints: POST /api/orders, PATCH /api/orders/{id}/cancel
 *   → Validated → OrderCommandService → PostgreSQL + Outbox → Saga starts
 *
 * Read endpoints: GET /api/orders/{id}, GET /api/orders/{id}/events, GET /api/orders/{id}/saga
 *   → OrderQueryService → Redis (with DB fallback)
 */
@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderCommandService commandService;
    private final OrderQueryService queryService;
    private final OrderEventStore eventStore;
    private final SagaStateStore sagaStateStore;

    // ─── Write Endpoints ──────────────────────────────────────────────────────

    /**
     * POST /api/orders
     * Create a new order and initiate the saga.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        CreateOrderCommand command = CreateOrderCommand.builder()
                .customerId(request.getCustomerId())
                .items(request.getItems().stream()
                        .map(i -> new OrderItem(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                        .toList())
                .paymentMethod(request.getPaymentMethod())
                .shippingAddress(new ShippingAddress(
                        request.getShippingAddress().getStreet(),
                        request.getShippingAddress().getCity(),
                        request.getShippingAddress().getCountry(),
                        request.getShippingAddress().getPostalCode()
                ))
                .correlationId(correlationId)
                .build();

        Order order = commandService.createOrder(command);

        OrderResponse response = OrderResponse.builder()
                .orderId(order.getId())
                .status(order.getStatus().name())
                .message("Order created. Processing initiated.")
                .correlationId(order.getCorrelationId())
                .links(Map.of(
                        "self", "/api/orders/" + order.getId(),
                        "events", "/api/orders/" + order.getId() + "/events",
                        "saga", "/api/orders/" + order.getId() + "/saga"
                ))
                .build();

        return ResponseEntity
                .created(URI.create("/api/orders/" + order.getId()))
                .body(response);
    }

    /**
     * PATCH /api/orders/{orderId}/cancel
     * Request cancellation of an in-flight order.
     */
    @PatchMapping("/{orderId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelOrder(
            @PathVariable String orderId,
            @RequestBody(required = false) Map<String, String> body) {

        String reason = body != null ? body.getOrDefault("reason", "Customer requested cancellation")
                                     : "Customer requested cancellation";

        commandService.updateStatus(orderId, Order.Status.CANCELLATION_REQUESTED);
        return ResponseEntity.ok(Map.of("orderId", orderId, "status", "CANCELLATION_REQUESTED", "reason", reason));
    }

    // ─── Read Endpoints ───────────────────────────────────────────────────────

    /**
     * GET /api/orders/{orderId}
     * Returns order from Redis read model (DB fallback).
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable String orderId) {
        return queryService.getOrder(orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/orders/{orderId}/events
     * Returns full event history (event sourcing audit trail).
     */
    @GetMapping("/{orderId}/events")
    public ResponseEntity<Map<String, Object>> getOrderEvents(@PathVariable String orderId) {
        List<Map<String, Object>> events = eventStore.getEventHistory(orderId);
        return ResponseEntity.ok(Map.of(
                "orderId", orderId,
                "events", events,
                "count", events.size()
        ));
    }

    /**
     * GET /api/orders/{orderId}/saga
     * Returns current saga state for debugging.
     */
    @GetMapping("/{orderId}/saga")
    public ResponseEntity<Object> getSagaState(@PathVariable String orderId) {
        return queryService.getOrder(orderId)
                .map(order -> {
                    String correlationId = (String) order.get("correlationId");
                    return sagaStateStore.load(correlationId)
                            .<ResponseEntity<Object>>map(state -> ResponseEntity.ok((Object) state))
                            .orElse(ResponseEntity.ok(Map.of(
                                    "orderId", orderId,
                                    "message", "Saga state not found (may have completed and been cleaned up)",
                                    "correlationId", correlationId
                            )));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/orders/customer/{customerId}
     * List orders for a customer.
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<Order>> getOrdersByCustomer(@PathVariable String customerId) {
        return ResponseEntity.ok(queryService.getOrdersByCustomer(customerId));
    }
}

// ─── Request / Response DTOs ───────────────────────────────────────────────────

@Data
class CreateOrderRequest {
    @NotBlank private String customerId;
    @NotEmpty private List<OrderItemRequest> items;
    @NotBlank private String paymentMethod;
    @Valid @NotNull private ShippingAddressRequest shippingAddress;

    @Data
    static class OrderItemRequest {
        @NotBlank private String productId;
        @Min(1) private int quantity;
        @NotNull @Positive private BigDecimal unitPrice;
    }

    @Data
    static class ShippingAddressRequest {
        @NotBlank private String street;
        @NotBlank private String city;
        @NotBlank @Size(min=2, max=2) private String country;
        @NotBlank private String postalCode;
    }
}

@Data
@lombok.Builder
class OrderResponse {
    private String orderId;
    private String status;
    private String message;
    private String correlationId;
    private Map<String, String> links;
}
