package com.platform.notification.template.engine;

import java.util.Map;

import com.platform.notification.template.NotificationContent;

public class TemplateEngine {
    public static NotificationContent render(String templateId, Map<String, Object> vars) {
        return switch (templateId) {
            case "order-confirmed" -> new NotificationContent(
                    "Your order " + vars.get("orderId") + " is confirmed! 🎉",
                    String.format("Your order %s for %s %s has been confirmed.", vars.get("orderId"), vars.get("currency"), vars.get("totalAmount")),
                    String.format("Order %s confirmed. Total: %s %s.", vars.get("orderId"), vars.get("currency"), vars.get("totalAmount"))
            );
            case "order-cancelled" -> new NotificationContent(
                    "Order " + vars.get("orderId") + " has been cancelled",
                    String.format("Your order %s was cancelled. Reason: %s. Any charges will be refunded.", vars.get("orderId"), vars.get("reason")),
                    String.format("Order %s cancelled. Reason: %s.", vars.get("orderId"), vars.get("reason"))
            );
            default -> new NotificationContent("Platform Notification", vars.toString(), vars.toString());
        };
    }
}