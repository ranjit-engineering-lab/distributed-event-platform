package com.platform.notification.channels.impl;

import com.platform.notification.channels.NotificationChannel;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EmailChannel implements NotificationChannel {
    @Override public String channel() { return "email"; }
    @Override public void send(String to, String subject, String body) {
        // Production: SES, SendGrid, Mailgun SDK call
        log.info("[EMAIL] To: {}, Subject: {}", to, subject);
    }
}