package com.platform.shared.outbox;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Outbox record — persisted alongside domain state in the same transaction.
 *
 * The Transactional Outbox Pattern:
 *   1. BEGIN TRANSACTION
 *      INSERT INTO domain_table (...)   -- your business state
 *      INSERT INTO outbox (event...)    -- event record
 *   2. COMMIT
 *   3. Background relay reads outbox, publishes to Kafka, marks published
 *
 * This guarantees atomic publish: either both the domain change and
 * the event record are committed, or neither is.
 */
@Entity
@Table(name = "outbox", indexes = {
    @Index(name = "idx_outbox_unpublished",
           columnList = "published_at, retry_count, created_at"),
    @Index(name = "idx_outbox_aggregate",
           columnList = "aggregate_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxRecord {

    /** Same UUID as the event ID — enables traceability */
    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 200)
    private String topic;

    /** Full serialized event JSON */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** NULL until successfully published to Kafka */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /** Exponential backoff: relay skips records where nextRetryAt > NOW() */
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onPrePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onPreUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public boolean isExhausted() {
        return retryCount >= 5;
    }

    /** Mark as successfully published */
    public void markPublished() {
        this.publishedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** Record a failure and schedule exponential backoff retry */
    public void recordFailure(String errorMessage) {
        this.retryCount++;
        this.lastError = errorMessage;
        // Backoff: 5s, 10s, 20s, 40s, 80s
        long backoffSeconds = (long) Math.pow(2, retryCount - 1) * 5L;
        this.nextRetryAt = Instant.now().plusSeconds(backoffSeconds);
        this.updatedAt = Instant.now();
    }
}
