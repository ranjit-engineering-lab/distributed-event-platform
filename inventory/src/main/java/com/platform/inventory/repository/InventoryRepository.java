package com.platform.inventory.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import com.platform.inventory.domain.InventoryItem;

import jakarta.persistence.LockModeType;

@Repository 
public interface InventoryRepository extends JpaRepository<InventoryItem, UUID> {
    @Lock(LockModeType.OPTIMISTIC) Optional<InventoryItem> findByProductId(String productId);
}