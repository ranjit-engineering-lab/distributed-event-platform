package com.platform.order.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.order.service.OrderEventStore;
import com.platform.shared.events.EventTypes;
import com.platform.shared.events.Events.*;
import com.platform.shared.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Order JPA Repository
 */
@Repository
interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByCustomerIdOrderByCreatedAtDesc(String customerId);
    List<Order> findByStatus(Order.Status status);
}

/**
 * Order Command Service — Write Side (CQRS)
 *
 * Handles state-changing operations:
 *  - CreateOrder: validates, persists order, appends to outbox, updates Redis read model
 *  - UpdateStatus: internal use for saga-driven status transitions
 *
 * All writes go through @Transactional — domain state + outbox record are atomic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCommandService {

    private final OrderRepository orderRepository;
    private final OutboxService outboxService;
    private final OrderEventStore eventStore;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * Create a new order.
     *
     * Transactional guarantee:
     *  1. INSERT into orders table
     *  2. INSERT into outbox table (same transaction)
     *  3. COMMIT → outbox relay publishes ORDER_CREATED to Kafka
     *  4. Update Redis read model (best-effort, outside transaction)
     *
     * If step 4 fails, the read model will be stale until refreshed on next read.
     */
    @Transactional
    public Order createOrder(CreateOrderCommand cmd) {
        String orderId = "ord_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String correlationId = cmd.getCorrelationId() != null
                ? cmd.getCorrelationId() : UUID.randomUUID().toString();

        BigDecimal totalAmount = cmd.getItems().stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 1. Serialize items and address for JSONB storage
        String itemsJson = serializeToJson(cmd.getItems());
        String addressJson = serializeToJson(cmd.getShippingAddress());

        // 2. Persist order (write model)
        Order order = Order.builder()
                .id(orderId)
                .customerId(cmd.getCustomerId())
                .status(Order.Status.PENDING)
                .itemsJson(itemsJson)
                .totalAmount(totalAmount)
                .currency("USD")
                .paymentMethod(cmd.getPaymentMethod())
                .shippingAddressJson(addressJson)
                .correlationId(correlationId)
                .build();

        orderRepository.save(order);

        // 3. Create the domain event
        OrderCreatedEvent event = new OrderCreatedEvent(
                orderId, cmd.getCustomerId(), cmd.getItems(),
                totalAmount, "USD", cmd.getPaymentMethod(),
                cmd.getShippingAddress(), correlationId
        );

        // 4. Append to outbox (SAME TRANSACTION — atomicity guaranteed)
        outboxService.append(
                orderId, "Order",
                EventTypes.ORDER_CREATED,
                EventTypes.TOPIC_ORDERS_CREATED,
                event
        );

        // 5. Write to event store (also same transaction)
        eventStore.append(orderId, event);

        log.info("Order created: orderId={}, customerId={}, totalAmount={}, correlationId={}",
                orderId, cmd.getCustomerId(), totalAmount, correlationId);

        // 6. Update Redis read model (outside transaction — eventual consistency is fine here)
        updateReadModel(order, cmd.getItems(), cmd.getShippingAddress());

        return order;
    }

    /**
     * Update order status — called by saga event handlers.
     */
    @Transactional
    public void updateStatus(String orderId, Order.Status newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        order.setStatus(newStatus);
        orderRepository.save(order);

        // Update read model
        updateReadModelStatus(orderId, newStatus.name());
    }

    private void updateReadModel(Order order, List<OrderItem> items, ShippingAddress address) {
        try {
            var readModel = new java.util.HashMap<String, Object>();
            readModel.put("orderId", order.getId());
            readModel.put("customerId", order.getCustomerId());
            readModel.put("status", order.getStatus().name());
            readModel.put("items", items);
            readModel.put("totalAmount", order.getTotalAmount());
            readModel.put("currency", order.getCurrency());
            readModel.put("shippingAddress", address);
            readModel.put("createdAt", order.getCreatedAt().toString());
            readModel.put("updatedAt", order.getUpdatedAt().toString());
            readModel.put("correlationId", order.getCorrelationId());

            redis.opsForValue().set(
                    "order:" + order.getId(),
                    objectMapper.writeValueAsString(readModel),
                    Duration.ofHours(24)
            );
        } catch (Exception e) {
            log.warn("Failed to update read model for order: orderId={}", order.getId(), e);
            // Non-fatal — reads will fall back to DB
        }
    }

    private void updateReadModelStatus(String orderId, String status) {
        try {
            String key = "order:" + orderId;
            String existing = redis.opsForValue().get(key);
            if (existing != null) {
                @SuppressWarnings("unchecked")
                var model = objectMapper.readValue(existing, java.util.HashMap.class);
                model.put("status", status);
                model.put("updatedAt", java.time.Instant.now().toString());
                redis.opsForValue().set(key, objectMapper.writeValueAsString(model), Duration.ofHours(24));
            }
        } catch (Exception e) {
            log.warn("Failed to update read model status: orderId={}", orderId, e);
        }
    }

    private String serializeToJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize object", e);
        }
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(String orderId) {
            super("Order not found: " + orderId);
        }
    }
}


