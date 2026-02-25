package com.platform.inventory.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.inventory.domain.InventoryItem;
import com.platform.inventory.domain.InventoryReservation;
import com.platform.inventory.exception.InsufficientStockException;
import com.platform.inventory.repository.InventoryRepository;
import com.platform.inventory.repository.ReservationRepository;
import com.platform.shared.events.EventTypes;
import com.platform.shared.events.Events.InventoryReleasedEvent;
import com.platform.shared.events.Events.InventoryReservationFailedEvent;
import com.platform.shared.events.Events.InventoryReserveRequestedEvent;
import com.platform.shared.events.Events.InventoryReservedEvent;
import com.platform.shared.events.Events.OrderItem;
import com.platform.shared.kafka.EventPublisher;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j @Service @RequiredArgsConstructor
public class InventoryService {

    private static final int MAX_OPTIMISTIC_RETRIES = 3;

    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * Reserve inventory for all order items.
     * Uses optimistic locking to prevent overselling in concurrent scenarios.
     *
     * Algorithm:
     *  1. Check if reservation already exists (idempotency)
     *  2. For each item, load + check + decrement atomically using @Version
     *  3. If any item fails → rollback all successful reservations in this attempt
     *  4. Publish RESERVED or RESERVATION_FAILED
     */
    @Transactional
    public void reserveInventory(InventoryReserveRequestedEvent event) throws Exception {
        String orderId = event.getOrderId();

        // Idempotency
        if (reservationRepository.findByOrderId(orderId).isPresent()) {
            log.info("Duplicate reservation request — skipping: orderId={}", orderId);
            return;
        }

        List<String> insufficientProductIds = new ArrayList<>();
        List<OrderItem> successfullyReserved = new ArrayList<>();

        for (OrderItem item : event.getItems()) {
            boolean reserved = false;
            for (int attempt = 0; attempt < MAX_OPTIMISTIC_RETRIES && !reserved; attempt++) {
                try {
                    InventoryItem inv = inventoryRepository.findByProductId(item.getProductId())
                            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + item.getProductId()));
                    inv.reserve(item.getQuantity());
                    inventoryRepository.save(inv);
                    successfullyReserved.add(item);
                    reserved = true;
                } catch (InsufficientStockException ex) {
                    insufficientProductIds.add(item.getProductId());
                    break;
                } catch (ObjectOptimisticLockingFailureException ex) {
                    log.debug("Optimistic lock conflict for product={}, attempt={}", item.getProductId(), attempt + 1);
                    if (attempt == MAX_OPTIMISTIC_RETRIES - 1) insufficientProductIds.add(item.getProductId());
                    Thread.sleep(10L * (attempt + 1));
                }
            }
        }

        if (!insufficientProductIds.isEmpty()) {
            // Rollback successful reservations
            for (OrderItem item : successfullyReserved) {
                inventoryRepository.findByProductId(item.getProductId()).ifPresent(inv -> {
                    inv.release(item.getQuantity());
                    inventoryRepository.save(inv);
                });
            }
            eventPublisher.publish(EventTypes.TOPIC_INVENTORY_RESERVATION_FAILED,
                    new InventoryReservationFailedEvent(orderId, "Insufficient stock", insufficientProductIds,
                            event.getCorrelationId(), event.getId()));
            log.warn("Inventory reservation failed: orderId={}, insufficientProducts={}", orderId, insufficientProductIds);
        } else {
            reservationRepository.save(InventoryReservation.builder()
                    .orderId(orderId).itemsJson(objectMapper.writeValueAsString(event.getItems()))
                    .status(InventoryReservation.Status.RESERVED).build());

            eventPublisher.publish(EventTypes.TOPIC_INVENTORY_RESERVED,
                    new InventoryReservedEvent(orderId, event.getItems(), event.getCorrelationId(), event.getId()));
            log.info("Inventory reserved: orderId={}", orderId);
        }
    }

    /** Release reserved inventory (saga compensation) */
    @Transactional
    public void releaseInventory(InventoryReleasedEvent event) {
        reservationRepository.findByOrderId(event.getOrderId()).ifPresent(reservation -> {
            if (reservation.getStatus() == InventoryReservation.Status.RELEASED) {
                log.info("Inventory already released: orderId={}", event.getOrderId());
                return;
            }
            for (OrderItem item : event.getItems()) {
                inventoryRepository.findByProductId(item.getProductId()).ifPresent(inv -> {
                    inv.release(item.getQuantity());
                    inventoryRepository.save(inv);
                });
            }
            reservation.setStatus(InventoryReservation.Status.RELEASED);
            reservationRepository.save(reservation);
            log.info("Inventory released: orderId={}", event.getOrderId());
        });
    }
}