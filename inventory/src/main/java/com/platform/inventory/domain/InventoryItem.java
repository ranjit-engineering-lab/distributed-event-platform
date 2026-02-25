package com.platform.inventory.domain;

import java.time.Instant;
import java.util.UUID;

import com.platform.inventory.exception.InsufficientStockException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "inventory")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class InventoryItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "product_id", nullable = false, unique = true, length = 100) private String productId;
    @Column(name = "sku", nullable = false, length = 100) private String sku;
    @Column(name = "available_quantity", nullable = false) private int availableQuantity;
    @Column(name = "reserved_quantity", nullable = false) private int reservedQuantity;
    @Column(name = "reorder_point", nullable = false) private int reorderPoint;

    /** Optimistic locking — @Version causes UPDATE WHERE version = $current
     * If concurrent modification: OptimisticLockException → retry */
    @Version @Column(name = "version", nullable = false) private int version;

    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public boolean hasAvailable(int quantity) { return availableQuantity >= quantity; }

    public void reserve(int quantity) {
        if (!hasAvailable(quantity)) throw new InsufficientStockException(productId, quantity, availableQuantity);
        availableQuantity -= quantity;
        reservedQuantity += quantity;
    }

    public void release(int quantity) {
        availableQuantity += quantity;
        reservedQuantity = Math.max(0, reservedQuantity - quantity);
    }
}