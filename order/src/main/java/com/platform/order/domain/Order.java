package com.platform.order.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Order JPA Entity — Write Model (CQRS)
 *
 * This is the authoritative source of truth for order data.
 * The read model (Redis) is a materialized view derived from this.
 */
@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_orders_customer_id", columnList = "customer_id"),
    @Index(name = "idx_orders_status", columnList = "status"),
    @Index(name = "idx_orders_correlation_id", columnList = "correlation_id"),
    @Index(name = "idx_orders_created_at", columnList = "created_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    public enum Status {
        PENDING,
        RESERVING_INVENTORY,
        PROCESSING_PAYMENT,
        CONFIRMED,
        CANCELLATION_REQUESTED,
        CANCELLED,
        FAILED
    }

    @Id
    @Column(name = "id", length = 50)
    private String id;

    @Column(name = "customer_id", nullable = false, length = 50)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items", nullable = false, columnDefinition = "jsonb")
    private String itemsJson;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "payment_method", nullable = false, length = 50)
    private String paymentMethod;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_address", columnDefinition = "jsonb")
    private String shippingAddressJson;

    @Column(name = "correlation_id", nullable = false, length = 36)
    private String correlationId;

    /** Optimistic locking — prevents concurrent modifications */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
