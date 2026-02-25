package com.platform.notification.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.notification.service.NotificationService;
import com.platform.shared.events.EventTypes;
import com.platform.shared.events.Events.NotificationSendEvent;
import com.platform.shared.kafka.IdempotencyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j @Component @RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationService notificationService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = EventTypes.TOPIC_NOTIFICATIONS_SEND, groupId = "notification-service")
    public void onNotificationSend(ConsumerRecord<String, String> record, Acknowledgment ack) {
        var h = record.headers().lastHeader("event-id");
        String eventId = h != null ? new String(h.value()) : null;
        if (eventId == null || idempotencyService.isDuplicate(eventId, EventTypes.NOTIFICATION_SEND)) {
            ack.acknowledge(); return;
        }
        try {
            NotificationSendEvent event = objectMapper.readValue(record.value(), NotificationSendEvent.class);
            notificationService.send(event);
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Notification processing failed: eventId={}", eventId, ex);
            throw new RuntimeException(ex);
        }
    }
}