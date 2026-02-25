package com.platform.notification.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.platform.notification.channels.NotificationChannel;
import com.platform.notification.template.NotificationContent;
import com.platform.notification.template.engine.TemplateEngine;
import com.platform.shared.events.Events.NotificationSendEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Notification Service — Purely Reactive
 *
 * No commands, no REST API, no saga participation.
 * Listens to NOTIFICATION_SEND events and delivers via appropriate channel.
 * Strategy pattern for channel selection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final Map<String, NotificationChannel> channels;

    @SuppressWarnings("unchecked")
    public void send(NotificationSendEvent event) {
        NotificationContent content = TemplateEngine.render(
                event.getTemplateId(),
                event.getVariables()
        );

        NotificationChannel channel = channels.getOrDefault(event.getChannel(), channels.get("email"));

        // In production: resolve customer email/phone from user service
        String to = resolveContact(event.getCustomerId(), event.getChannel());

        String body = "sms".equals(event.getChannel()) ? content.sms() : content.body();

        channel.send(to, content.subject(), body);

        log.info("Notification sent: customerId={}, channel={}, template={}",
                event.getCustomerId(), event.getChannel(), event.getTemplateId());
    }

    private String resolveContact(String customerId, String channel) {
        // Production: fetch from Redis cache → user service API
        return "sms".equals(channel)
                ? "+1555" + customerId.hashCode() % 10000000
                : customerId + "@platform.example.com";
    }
}