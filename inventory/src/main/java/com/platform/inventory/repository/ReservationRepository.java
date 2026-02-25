package com.platform.inventory.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.platform.inventory.domain.InventoryReservation;

@Repository 
public interface ReservationRepository extends JpaRepository<InventoryReservation, UUID> {
    Optional<InventoryReservation> findByOrderId(String orderId);
}