package com.platform.order.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Order Query Service — Read Side (CQRS)
 *
 * All read operations are served from Redis read model first.
 * Falls back to PostgreSQL if Redis is cold (cache miss, expired TTL, restart).
 *
 * This means read performance is O(1) Redis lookups regardless of DB size.
 * The read model stays consistent via the saga's status update calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    /**
     * Get order by ID.
     * Tries Redis first, falls back to DB and rebuilds cache.
     *
     * @return Map with _source indicating "read-model" or "database"
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getOrder(String orderId) {
        // ── Read Model (Redis) ────────────────────────────────────────────────
        String cached = redis.opsForValue().get("order:" + orderId);
        if (cached != null) {
            try {
                Map<String, Object> result = objectMapper.readValue(cached, Map.class);
                result.put("_source", "read-model");
                return Optional.of(result);
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse cached order: orderId={}", orderId);
            }
        }

        // ── Database Fallback ─────────────────────────────────────────────────
        return orderRepository.findById(orderId).map(order -> {
            Map<String, Object> result = Map.of(
                    "orderId", order.getId(),
                    "customerId", order.getCustomerId(),
                    "status", order.getStatus().name(),
                    "totalAmount", order.getTotalAmount(),
                    "currency", order.getCurrency(),
                    "paymentMethod", order.getPaymentMethod(),
                    "createdAt", order.getCreatedAt().toString(),
                    "updatedAt", order.getUpdatedAt().toString(),
                    "correlationId", order.getCorrelationId(),
                    "_source", "database"
            );
            // Rebuild cache
            try {
                redis.opsForValue().set("order:" + orderId, objectMapper.writeValueAsString(result));
            } catch (Exception ignored) {}
            return result;
        });
    }

    public java.util.List<Order> getOrdersByCustomer(String customerId) {
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }
}
