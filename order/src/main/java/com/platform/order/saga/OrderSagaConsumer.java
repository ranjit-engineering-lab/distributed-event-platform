package com.platform.order.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.order.domain.Order;
import com.platform.order.domain.OrderCommandService;
import com.platform.order.service.OrderEventStore;
import com.platform.shared.events.EventTypes;
import com.platform.shared.events.Events.*;
import com.platform.shared.kafka.IdempotencyService;
import com.platform.shared.saga.OrderSagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Order Service Kafka Consumers
 *
 * Listens to saga response events and routes them to the orchestrator.
 * Also updates the order write model and event store on state transitions.
 *
 * Design:
 * - Idempotency check FIRST — skip duplicates before any processing
 * - Event store append — always record the event for audit trail
 * - Saga orchestrator — advance or compensate the saga
 * - Status update — keep order write model in sync
 *
 * Manual acknowledgment (AckMode.MANUAL_IMMEDIATE):
 * Offsets are committed ONLY after successful processing.
 * If handler throws, the message will be redelivered.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSagaConsumer {

    private final OrderSagaOrchestrator sagaOrchestrator;
    private final OrderCommandService commandService;
    private final OrderEventStore eventStore;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = EventTypes.TOPIC_INVENTORY_RESERVED,
            groupId = "order-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onInventoryReserved(ConsumerRecord<String, String> record, Acknowledgment ack) {
        processEvent(record, ack, EventTypes.INVENTORY_RESERVED, payload -> {
            InventoryReservedEvent event = objectMapper.readValue(payload, InventoryReservedEvent.class);
            commandService.updateStatus(event.getOrderId(), Order.Status.RESERVING_INVENTORY);
            eventStore.append(event.getOrderId(), event);
            sagaOrchestrator.onInventoryReserved(event);
        });
    }

    @KafkaListener(
            topics = EventTypes.TOPIC_INVENTORY_RESERVATION_FAILED,
            groupId = "order-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onInventoryReservationFailed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        processEvent(record, ack, EventTypes.INVENTORY_RESERVATION_FAILED, payload -> {
            InventoryReservationFailedEvent event = objectMapper.readValue(payload, InventoryReservationFailedEvent.class);
            eventStore.append(event.getOrderId(), event);
            sagaOrchestrator.onInventoryReservationFailed(event);
        });
    }

    @KafkaListener(
            topics = EventTypes.TOPIC_PAYMENTS_COMPLETED,
            groupId = "order-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onPaymentCompleted(ConsumerRecord<String, String> record, Acknowledgment ack) {
        processEvent(record, ack, EventTypes.PAYMENT_COMPLETED, payload -> {
            PaymentCompletedEvent event = objectMapper.readValue(payload, PaymentCompletedEvent.class);
            commandService.updateStatus(event.getOrderId(), Order.Status.PROCESSING_PAYMENT);
            eventStore.append(event.getOrderId(), event);
            sagaOrchestrator.onPaymentCompleted(event);
        });
    }

    @KafkaListener(
            topics = EventTypes.TOPIC_PAYMENTS_FAILED,
            groupId = "order-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onPaymentFailed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        processEvent(record, ack, EventTypes.PAYMENT_FAILED, payload -> {
            PaymentFailedEvent event = objectMapper.readValue(payload, PaymentFailedEvent.class);
            eventStore.append(event.getOrderId(), event);
            sagaOrchestrator.onPaymentFailed(event);
        });
    }

    @KafkaListener(
            topics = EventTypes.TOPIC_ORDERS_CONFIRMED,
            groupId = "order-service-confirmed",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderConfirmed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        processEvent(record, ack, EventTypes.ORDER_CONFIRMED, payload -> {
            OrderConfirmedEvent event = objectMapper.readValue(payload, OrderConfirmedEvent.class);
            commandService.updateStatus(event.getOrderId(), Order.Status.CONFIRMED);
            eventStore.append(event.getOrderId(), event);
            sagaOrchestrator.onOrderConfirmed(event);
        });
    }

    @KafkaListener(
            topics = EventTypes.TOPIC_ORDERS_CANCELLED,
            groupId = "order-service-cancelled",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderCancelled(ConsumerRecord<String, String> record, Acknowledgment ack) {
        processEvent(record, ack, EventTypes.ORDER_CANCELLED, payload -> {
            OrderCancelledEvent event = objectMapper.readValue(payload, OrderCancelledEvent.class);
            commandService.updateStatus(event.getOrderId(), Order.Status.CANCELLED);
            eventStore.append(event.getOrderId(), event);
        });
    }

    /**
     * Shared processing wrapper:
     * 1. Extract event ID from headers
     * 2. Check idempotency — skip duplicates
     * 3. Execute handler
     * 4. Acknowledge offset (commit to Kafka)
     * 5. On failure: throw (do NOT ack) — message will be redelivered
     */
    private void processEvent(ConsumerRecord<String, String> record, Acknowledgment ack,
                               String topic, CheckedConsumer<String> handler) {
        // Extract event ID from headers for idempotency
        String eventId = extractHeader(record, "event-id");
        if (eventId == null) {
            log.warn("Message missing event-id header: topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
            ack.acknowledge(); // Skip malformed messages
            return;
        }

        // Idempotency check
        if (idempotencyService.isDuplicate(eventId, topic)) {
            log.debug("Duplicate event skipped: eventId={}, topic={}", eventId, topic);
            ack.acknowledge();
            return;
        }

        try {
            handler.accept(record.value());
            ack.acknowledge(); // Commit offset only after successful processing
            log.debug("Event processed: eventId={}, topic={}, partition={}, offset={}",
                    eventId, topic, record.partition(), record.offset());
        } catch (Exception ex) {
            log.error("Failed to process event: eventId={}, topic={}, error={}",
                    eventId, topic, ex.getMessage(), ex);
            // Don't ack — Kafka will redeliver. The error handler in KafkaConfig
            // manages retry limits and DLQ routing.
            throw new RuntimeException("Event processing failed", ex);
        }
    }

    private String extractHeader(ConsumerRecord<?, ?> record, String headerName) {
        var header = record.headers().lastHeader(headerName);
        return header != null ? new String(header.value()) : null;
    }

    @FunctionalInterface
    interface CheckedConsumer<T> {
        void accept(T t) throws Exception;
    }
}
