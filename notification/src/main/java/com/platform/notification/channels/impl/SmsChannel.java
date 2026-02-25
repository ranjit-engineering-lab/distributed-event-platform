package com.platform.notification.channels.impl;

import com.platform.notification.channels.NotificationChannel;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SmsChannel implements NotificationChannel {
    @Override public String channel() { return "sms"; }
    @Override public void send(String to, String subject, String body) {
        // Production: Twilio, AWS SNS SDK call
        log.info("[SMS] To: {}, Body: {}", to, body.substring(0, Math.min(80, body.length())));
    }
}