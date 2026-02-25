package com.platform.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "refunds")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Refund {
    @Id @Column(name = "id", length = 50) private String id;
    @Column(name = "payment_id", nullable = false, unique = true, length = 50) private String paymentId;
    @Column(name = "order_id", nullable = false, length = 50) private String orderId;
    @Column(name = "amount", nullable = false, precision = 12, scale = 2) private BigDecimal amount;
    @Column(name = "currency", nullable = false, length = 3) private String currency;
    @Column(name = "status", nullable = false, length = 50) private String status;
    @Column(name = "created_at") private Instant createdAt;
    @PrePersist void prePersist() { if (createdAt == null) createdAt = Instant.now(); }
}