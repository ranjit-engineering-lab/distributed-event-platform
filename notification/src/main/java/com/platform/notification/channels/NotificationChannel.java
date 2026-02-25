package com.platform.notification.channels;

public interface NotificationChannel {
    String channel();
    void send(String to, String subject, String body);
}