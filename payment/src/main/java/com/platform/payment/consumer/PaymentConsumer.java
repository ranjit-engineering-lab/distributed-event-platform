package com.platform.payment.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.payment.service.PaymentService;
import com.platform.shared.events.EventTypes;
import com.platform.shared.events.Events.PaymentInitiatedEvent;
import com.platform.shared.events.Events.PaymentRefundedEvent;
import com.platform.shared.kafka.IdempotencyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
class PaymentConsumer {

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = EventTypes.TOPIC_PAYMENTS_INITIATED, groupId = "payment-service")
    public void onPaymentInitiated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        processEvent(record, ack, EventTypes.PAYMENT_INITIATED, payload -> {
            PaymentInitiatedEvent event = objectMapper.readValue(payload, PaymentInitiatedEvent.class);
            paymentService.processPayment(event);
        });
    }

    @KafkaListener(topics = EventTypes.TOPIC_PAYMENTS_REFUNDED, groupId = "payment-service")
    public void onPaymentRefunded(ConsumerRecord<String, String> record, Acknowledgment ack) {
        processEvent(record, ack, EventTypes.PAYMENT_REFUNDED, payload -> {
            PaymentRefundedEvent event = objectMapper.readValue(payload, PaymentRefundedEvent.class);
            paymentService.processRefund(event);
        });
    }

    private void processEvent(ConsumerRecord<String, String> record, Acknowledgment ack,
                               String topic, CheckedConsumer<String> handler) {
        var eventIdHeader = record.headers().lastHeader("event-id");
        String eventId = eventIdHeader != null ? new String(eventIdHeader.value()) : null;
        if (eventId == null || idempotencyService.isDuplicate(eventId, topic)) {
            ack.acknowledge();
            return;
        }
        try {
            handler.accept(record.value());
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Payment event processing failed: eventId={}, topic={}", eventId, topic, ex);
            throw new RuntimeException(ex);
        }
    }

    @FunctionalInterface interface CheckedConsumer<T> { void accept(T t) throws Exception; }
}