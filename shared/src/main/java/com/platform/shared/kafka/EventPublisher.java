package com.platform.shared.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.shared.events.DomainEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Platform-level Kafka Producer.
 *
 * Wraps Spring's KafkaTemplate with:
 *  - Automatic event serialization
 *  - Trace context propagation via Kafka headers
 *  - Prometheus metrics (publish rate, latency, error rate)
 *  - Structured logging with event metadata
 *
 * The underlying KafkaTemplate is configured as an idempotent producer
 * (enable.idempotence=true) to guarantee exactly-once writes to the broker.
 */
@Slf4j
@Component
public class EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Counter publishSuccessCounter;
    private final Counter publishErrorCounter;
    private final Timer publishTimer;

    public EventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                          ObjectMapper objectMapper,
                          MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.publishSuccessCounter = Counter.builder("kafka.messages.published")
                .tag("status", "success")
                .description("Total Kafka messages published successfully")
                .register(meterRegistry);
        this.publishErrorCounter = Counter.builder("kafka.messages.published")
                .tag("status", "error")
                .description("Total Kafka message publish failures")
                .register(meterRegistry);
        this.publishTimer = Timer.builder("kafka.publish.duration")
                .description("Time to publish a message to Kafka")
                .register(meterRegistry);
    }

    /**
     * Publish a domain event to the specified Kafka topic.
     * Uses correlationId as the partition key to ensure ordering of related events.
     *
     * @param topic  Kafka topic name (use EventTypes constants)
     * @param event  CloudEvent-compliant domain event
     * @return CompletableFuture that completes when the broker acknowledges
     */
    public CompletableFuture<SendResult<String, String>> publish(String topic, DomainEvent event) {
        return publish(topic, event, event.getCorrelationId());
    }

    public CompletableFuture<SendResult<String, String>> publish(String topic, DomainEvent event, String partitionKey) {
        Timer.Sample sample = Timer.start();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event: eventId={}, type={}", event.getId(), event.getType(), e);
            publishErrorCounter.increment();
            return CompletableFuture.failedFuture(e);
        }

        // Build producer record with trace headers for distributed tracing
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, partitionKey, payload);
        record.headers()
                .add(new RecordHeader("event-type", event.getType().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("event-id", event.getId().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("event-version", String.valueOf(event.getVersion()).getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("correlation-id", event.getCorrelationId().getBytes(StandardCharsets.UTF_8)));

        if (event.getCausationId() != null) {
            record.headers().add(new RecordHeader("causation-id",
                    event.getCausationId().getBytes(StandardCharsets.UTF_8)));
        }

        return kafkaTemplate.send(record)
                .whenComplete((result, ex) -> {
                    sample.stop(publishTimer);
                    if (ex == null) {
                        publishSuccessCounter.increment();
                        log.debug("Event published: topic={}, eventId={}, type={}, correlationId={}, partition={}, offset={}",
                                topic, event.getId(), event.getType(), event.getCorrelationId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        publishErrorCounter.increment();
                        log.error("Failed to publish event: topic={}, eventId={}, type={}, error={}",
                                topic, event.getId(), event.getType(), ex.getMessage(), ex);
                    }
                });
    }

    /**
     * Synchronous publish — blocks until broker acknowledges.
     * Use for critical events where you need guaranteed delivery before proceeding.
     */
    public void publishAndWait(String topic, DomainEvent event) {
        try {
            publish(topic, event).get();
        } catch (Exception e) {
            throw new EventPublishException("Failed to publish event synchronously: " + event.getType(), e);
        }
    }

    public static class EventPublishException extends RuntimeException {
        public EventPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
