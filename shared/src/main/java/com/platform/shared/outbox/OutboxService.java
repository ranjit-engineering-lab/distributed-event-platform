package com.platform.shared.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.shared.events.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Outbox Append Service
 *
 * Called within a domain service's @Transactional method to atomically
 * write an outbox record alongside domain state changes.
 *
 * Usage:
 * <pre>
 * {@literal @}Transactional
 * public Order createOrder(CreateOrderCommand cmd) {
 *     Order order = orderRepository.save(new Order(cmd));  // domain write
 *     outboxService.append(                                 // event record (same tx)
 *         order.getId(), "Order", EventTypes.ORDER_CREATED,
 *         EventTypes.ORDER_CREATED, event
 *     );
 *     return order;
 * }
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Append an event to the outbox within the current transaction.
     * MUST be called inside an active {@literal @}Transactional context.
     *
     * @param aggregateId   Domain aggregate ID (e.g., orderId)
     * @param aggregateType Domain aggregate type (e.g., "Order")
     * @param eventType     Event type string (use EventTypes constants)
     * @param topic         Kafka topic to publish to
     * @param event         The domain event to publish
     */
    public void append(String aggregateId, String aggregateType,
                       String eventType, String topic, DomainEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize event to JSON: " + eventType, e);
        }

        OutboxRecord record = OutboxRecord.builder()
                .id(event.getId())         // outbox ID == event ID for traceability
                .aggregateId(aggregateId)
                .aggregateType(aggregateType)
                .eventType(eventType)
                .topic(topic)
                .payload(payload)
                .retryCount(0)
                .build();

        outboxRepository.save(record);

        log.debug("Outbox record appended: eventId={}, type={}, aggregateId={}",
                event.getId(), eventType, aggregateId);
    }
}
