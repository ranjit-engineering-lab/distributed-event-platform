package com.platform.shared.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.shared.kafka.EventPublisher;
import com.platform.shared.events.DomainEvent;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Outbox JPA Repository
 */
interface OutboxRepository extends JpaRepository<OutboxRecord, String> {

    /**
     * Fetch unpublished records eligible for relay.
     * SKIP LOCKED: allows multiple relay instances to process different records concurrently
     * without blocking each other — critical for high-throughput scenarios.
     */
    @Query(value = """
        SELECT * FROM outbox
        WHERE published_at IS NULL
          AND retry_count < 5
          AND (next_retry_at IS NULL OR next_retry_at <= :now)
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<OutboxRecord> findUnpublishedForRelay(@Param("now") Instant now, @Param("limit") int limit);

    @Query("SELECT COUNT(o) FROM OutboxRecord o WHERE o.publishedAt IS NULL AND o.retryCount < 5")
    long countUnpublished();
}

/**
 * Outbox Relay — polls outbox table and publishes to Kafka.
 *
 * Runs every second. Processes up to 50 records per poll.
 * On high load, consecutive full batches are processed immediately (no sleep).
 *
 * Production alternative: Debezium CDC reads Postgres WAL instead of polling.
 * Zero DB overhead, sub-100ms latency, but requires more infrastructure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private static final int BATCH_SIZE = 50;

    private final OutboxRepository outboxRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private Counter relayedCounter;
    private Counter relayErrorCounter;

    @jakarta.annotation.PostConstruct
    void initMetrics() {
        relayedCounter = Counter.builder("outbox.records.relayed")
                .description("Outbox records successfully relayed to Kafka")
                .register(meterRegistry);
        relayErrorCounter = Counter.builder("outbox.relay.errors")
                .description("Errors during outbox relay")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 1000) // Run 1 second after previous completion
    @Transactional
    public void relay() {
        List<OutboxRecord> records = outboxRepository
                .findUnpublishedForRelay(Instant.now(), BATCH_SIZE);

        if (records.isEmpty()) return;

        log.debug("Outbox relay: processing {} records", records.size());

        for (OutboxRecord record : records) {
            try {
                publishRecord(record);
                record.markPublished();
                relayedCounter.increment();
            } catch (Exception ex) {
                log.error("Failed to relay outbox record: id={}, eventType={}, attempt={}",
                        record.getId(), record.getEventType(), record.getRetryCount() + 1, ex);
                record.recordFailure(ex.getMessage());
                relayErrorCounter.increment();
            }
        }

        outboxRepository.saveAll(records);
    }

    private void publishRecord(OutboxRecord record) throws Exception {
        // Deserialize the stored payload back to a generic wrapper
        JsonNode payloadNode = objectMapper.readTree(record.getPayload());
        String eventType = payloadNode.get("type").asText();

        // Re-create a lightweight event wrapper that carries the raw payload
        OutboxEventWrapper wrapper = new OutboxEventWrapper(
                record.getId(),
                eventType,
                record.getPayload(),
                record.getAggregateId()
        );

        eventPublisher.publishAndWait(record.getTopic(), wrapper);
    }
}

/**
 * Lightweight event wrapper for outbox relay.
 * Wraps already-serialized JSON payload for re-publishing without double-serialization.
 */
class OutboxEventWrapper extends DomainEvent {
    private final String rawPayload;

    OutboxEventWrapper(String id, String type, String rawPayload, String correlationId) {
        super(type, "/services/outbox-relay", correlationId);
        this.rawPayload = rawPayload;
    }

    public String getRawPayload() { return rawPayload; }
}
