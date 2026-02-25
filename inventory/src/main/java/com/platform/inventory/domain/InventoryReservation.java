package com.platform.inventory.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "inventory_reservations")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class InventoryReservation {
    public enum Status { RESERVED, RELEASED, CONSUMED }
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "order_id", nullable = false, unique = true, length = 50) private String orderId;
    @Column(name = "items", nullable = false, columnDefinition = "jsonb") private String itemsJson;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false) private Status status;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void prePersist() { Instant n = Instant.now(); createdAt = n; updatedAt = n; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
}