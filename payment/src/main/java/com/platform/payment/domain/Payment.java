package com.platform.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payments", indexes = {
    @Index(name = "idx_payments_order_id", columnList = "order_id"),
    @Index(name = "idx_payments_customer_id", columnList = "customer_id")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Payment {
    public enum Status { COMPLETED, FAILED, REFUNDED }

    @Id @Column(name = "id", length = 50) private String id;
    @Column(name = "order_id", nullable = false, unique = true, length = 50) private String orderId;
    @Column(name = "customer_id", nullable = false, length = 50) private String customerId;
    @Column(name = "amount", nullable = false, precision = 12, scale = 2) private BigDecimal amount;
    @Column(name = "currency", nullable = false, length = 3) private String currency;
    @Column(name = "payment_method", nullable = false, length = 50) private String paymentMethod;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false) private Status status;
    @Column(name = "failure_reason", columnDefinition = "text") private String failureReason;
    @Column(name = "gateway_ref", length = 200) private String gatewayRef;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void prePersist() { Instant now = Instant.now(); if (createdAt == null) createdAt = now; updatedAt = now; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
}