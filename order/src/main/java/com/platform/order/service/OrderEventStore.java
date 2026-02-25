package com.platform.order.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import com.platform.order.repository.OrderEventRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.order.domain.OrderEventRecord;
import com.platform.shared.events.DomainEvent;

import lombok.RequiredArgsConstructor;

/**
 * Order Event Store Service
 */
@Service
@RequiredArgsConstructor
public class OrderEventStore {

    private final OrderEventRecordRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Append an event to the order's event log.
     * Must be called within an active @Transactional context.
     *
     * Sequence numbers are assigned per-order and are monotonically increasing.
     * ON CONFLICT DO NOTHING ensures idempotency (same event ID is a no-op).
     */
    public void append(String orderId, DomainEvent event) {
        // Check for duplicate event (idempotent append)
        if (repository.existsById(event.getId())) {
            return;
        }

        int nextSequence = repository.findMaxSequenceByOrderId(orderId) + 1;
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event for event store", e);
        }

        OrderEventRecord record = OrderEventRecord.builder()
                .id(event.getId())
                .orderId(orderId)
                .eventType(event.getType())
                .payload(payload)
                .sequence(nextSequence)
                .correlationId(event.getCorrelationId())
                .causationId(event.getCausationId())
                .createdAt(event.getTime())
                .build();

        repository.save(record);
    }

    /**
     * Get all events for an order in sequence order.
     */
    public List<Map<String, Object>> getEventHistory(String orderId) {
    	Stream<Map<String, Object>> streamOfObjects = repository.findByOrderIdOrderBySequenceAsc(orderId)
                .stream()
                .map(r -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> event = objectMapper.readValue(r.getPayload(), Map.class);
                        event.put("_sequence", r.getSequence());
                        return (Map<String, Object>) event;
                    } catch (JsonProcessingException e) {
                        return Map.of("id", r.getId(), "type", r.getEventType(), "error", "parse failed");
                    }
                });
    	return streamOfObjects.toList();
                
    }

    /**
     * Reconstruct order state by replaying all events (event sourcing).
     * Returns a map representing the final reconstructed state.
     */
    public Map<String, Object> replayToCurrentState(String orderId) {
        List<OrderEventRecord> events = repository.findByOrderIdOrderBySequenceAsc(orderId);
        return events.stream().reduce(
                (Map<String, Object>) new java.util.HashMap<String, Object>(),
                (state, record) -> applyEvent(state, record.getEventType(), record.getPayload()),
                (a, b) -> b
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyEvent(Map<String, Object> state, String eventType, String payload) {
        try {
            Map<String, Object> event = objectMapper.readValue(payload, Map.class);
            Map<String, Object> data = (Map<String, Object>) event.getOrDefault("data", event);
            Map<String, Object> newState = new java.util.HashMap<>(state);

            switch (eventType) {
                case "orders.created" -> {
                    newState.put("orderId", data.get("orderId"));
                    newState.put("customerId", data.get("customerId"));
                    newState.put("status", "PENDING");
                    newState.put("items", data.get("items"));
                    newState.put("totalAmount", data.get("totalAmount"));
                    newState.put("currency", data.get("currency"));
                    newState.put("createdAt", event.get("time"));
                }
                case "inventory.reserved"   -> newState.put("status", "INVENTORY_RESERVED");
                case "payments.completed"   -> { newState.put("status", "PAYMENT_COMPLETED"); newState.put("paymentId", data.get("paymentId")); }
                case "orders.confirmed"     -> newState.put("status", "CONFIRMED");
                case "orders.cancelled"     -> { newState.put("status", "CANCELLED"); newState.put("cancellationReason", data.get("reason")); }
                case "payments.failed", "inventory.reservation-failed" -> newState.put("status", "FAILED");
            }
            newState.put("lastEventType", eventType);
            newState.put("lastEventTime", event.get("time"));
            return newState;
        } catch (Exception e) {
            return state;
        }
    }
}
