package com.platform.inventory.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.inventory.service.InventoryService;
import com.platform.shared.events.EventTypes;
import com.platform.shared.events.Events.InventoryReleasedEvent;
import com.platform.shared.events.Events.InventoryReserveRequestedEvent;
import com.platform.shared.kafka.IdempotencyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j @Component @RequiredArgsConstructor
class InventoryConsumer {

    private final InventoryService inventoryService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = EventTypes.TOPIC_INVENTORY_RESERVE_REQUESTED, groupId = "inventory-service")
    public void onReserveRequested(ConsumerRecord<String, String> record, Acknowledgment ack) {
        process(record, ack, EventTypes.INVENTORY_RESERVE_REQUESTED, payload -> {
            InventoryReserveRequestedEvent event = objectMapper.readValue(payload, InventoryReserveRequestedEvent.class);
            inventoryService.reserveInventory(event);
        });
    }

    @KafkaListener(topics = EventTypes.TOPIC_INVENTORY_RELEASED, groupId = "inventory-service")
    public void onInventoryReleased(ConsumerRecord<String, String> record, Acknowledgment ack) {
        process(record, ack, EventTypes.INVENTORY_RELEASED, payload -> {
            InventoryReleasedEvent event = objectMapper.readValue(payload, InventoryReleasedEvent.class);
            inventoryService.releaseInventory(event);
        });
    }

    private void process(ConsumerRecord<String, String> record, Acknowledgment ack,
                          String topic, CheckedConsumer<String> handler) {
        var h = record.headers().lastHeader("event-id");
        String eventId = h != null ? new String(h.value()) : null;
        if (eventId == null || idempotencyService.isDuplicate(eventId, topic)) { ack.acknowledge(); return; }
        try { handler.accept(record.value()); ack.acknowledge(); }
        catch (Exception ex) { throw new RuntimeException(ex); }
    }

    @FunctionalInterface interface CheckedConsumer<T> { void accept(T t) throws Exception; }
}