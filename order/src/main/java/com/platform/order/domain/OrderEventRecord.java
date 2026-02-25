package com.platform.order.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Order Event Store — Append-Only Event Log
 *
 * Every state change to an Order is recorded as an immutable event.
 * Provides:
 *  - Full audit trail: who did what, when
 *  - Point-in-time reconstruction: replay events to get state at any moment
 *  - Event sourcing: the event log IS the source of truth (not just the snapshot)
 *  - Debug tool: replay events from production to reproduce a bug locally
 */
@Entity
@Table(name = "order_events", indexes = {
    @Index(name = "idx_order_events_order_id", columnList = "order_id, sequence"),
    @Index(name = "idx_order_events_type", columnList = "event_type"),
    @Index(name = "idx_order_events_correlation_id", columnList = "correlation_id")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEventRecord {

    @Id
    @Column(name = "id", length = 36)
    private String id;                 // Same as event ID

    @Column(name = "order_id", nullable = false, length = 50)
    private String orderId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;            // Full serialized event JSON

    @Column(name = "sequence", nullable = false)
    private int sequence;              // Monotonically increasing within an order

    @Column(name = "correlation_id", length = 36)
    private String correlationId;

    @Column(name = "causation_id", length = 36)
    private String causationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}



