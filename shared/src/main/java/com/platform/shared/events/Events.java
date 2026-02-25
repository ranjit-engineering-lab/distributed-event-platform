package com.platform.shared.events;

import lombok.Getter;
import java.math.BigDecimal;
import java.util.List;

/**
 * All domain event payload classes.
 * Each event extends DomainEvent and adds its specific payload.
 *
 * Naming: {Noun}{PastTense}Event — e.g. OrderCreatedEvent, PaymentCompletedEvent
 */
public final class Events {

    private Events() {}

    // ─── Order Events ──────────────────────────────────────────────────────────

    @Getter
    public static class OrderCreatedEvent extends DomainEvent {
        private final String orderId;
        private final String customerId;
        private final List<OrderItem> items;
        private final BigDecimal totalAmount;
        private final String currency;
        private final String paymentMethod;
        private final ShippingAddress shippingAddress;

        public OrderCreatedEvent(String orderId, String customerId, List<OrderItem> items,
                                 BigDecimal totalAmount, String currency, String paymentMethod,
                                 ShippingAddress shippingAddress, String correlationId) {
            super(EventTypes.ORDER_CREATED, "/services/order-service", correlationId);
            this.orderId = orderId;
            this.customerId = customerId;
            this.items = items;
            this.totalAmount = totalAmount;
            this.currency = currency;
            this.paymentMethod = paymentMethod;
            this.shippingAddress = shippingAddress;
        }
    }

    @Getter
    public static class OrderConfirmedEvent extends DomainEvent {
        private final String orderId;
        private final String customerId;

        public OrderConfirmedEvent(String orderId, String customerId, String correlationId, String causationId) {
            super(EventTypes.ORDER_CONFIRMED, "/services/order-service", correlationId, causationId, 1);
            this.orderId = orderId;
            this.customerId = customerId;
        }
    }

    @Getter
    public static class OrderCancelledEvent extends DomainEvent {
        private final String orderId;
        private final String customerId;
        private final String reason;

        public OrderCancelledEvent(String orderId, String customerId, String reason,
                                   String correlationId, String causationId) {
            super(EventTypes.ORDER_CANCELLED, "/services/order-service", correlationId, causationId, 1);
            this.orderId = orderId;
            this.customerId = customerId;
            this.reason = reason;
        }
    }

    // ─── Payment Events ────────────────────────────────────────────────────────

    @Getter
    public static class PaymentInitiatedEvent extends DomainEvent {
        private final String orderId;
        private final String customerId;
        private final BigDecimal amount;
        private final String currency;
        private final String paymentMethod;

        public PaymentInitiatedEvent(String orderId, String customerId, BigDecimal amount,
                                     String currency, String paymentMethod,
                                     String correlationId, String causationId) {
            super(EventTypes.PAYMENT_INITIATED, "/services/order-service", correlationId, causationId, 1);
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.currency = currency;
            this.paymentMethod = paymentMethod;
        }
    }

    @Getter
    public static class PaymentCompletedEvent extends DomainEvent {
        private final String orderId;
        private final String paymentId;
        private final BigDecimal amount;
        private final String currency;

        public PaymentCompletedEvent(String orderId, String paymentId, BigDecimal amount,
                                     String currency, String correlationId, String causationId) {
            super(EventTypes.PAYMENT_COMPLETED, "/services/payment-service", correlationId, causationId, 1);
            this.orderId = orderId;
            this.paymentId = paymentId;
            this.amount = amount;
            this.currency = currency;
        }
    }

    @Getter
    public static class PaymentFailedEvent extends DomainEvent {
        private final String orderId;
        private final String reason;

        public PaymentFailedEvent(String orderId, String reason, String correlationId, String causationId) {
            super(EventTypes.PAYMENT_FAILED, "/services/payment-service", correlationId, causationId, 1);
            this.orderId = orderId;
            this.reason = reason;
        }
    }

    @Getter
    public static class PaymentRefundedEvent extends DomainEvent {
        private final String orderId;
        private final String paymentId;
        private final BigDecimal amount;
        private final String currency;

        public PaymentRefundedEvent(String orderId, String paymentId, BigDecimal amount,
                                    String currency, String correlationId, String causationId) {
            super(EventTypes.PAYMENT_REFUNDED, "/services/payment-service", correlationId, causationId, 1);
            this.orderId = orderId;
            this.paymentId = paymentId;
            this.amount = amount;
            this.currency = currency;
        }
    }

    // ─── Inventory Events ──────────────────────────────────────────────────────

    @Getter
    public static class InventoryReserveRequestedEvent extends DomainEvent {
        private final String orderId;
        private final List<OrderItem> items;

        public InventoryReserveRequestedEvent(String orderId, List<OrderItem> items,
                                              String correlationId, String causationId) {
            super(EventTypes.INVENTORY_RESERVE_REQUESTED, "/services/order-service", correlationId, causationId, 1);
            this.orderId = orderId;
            this.items = items;
        }
    }

    @Getter
    public static class InventoryReservedEvent extends DomainEvent {
        private final String orderId;
        private final List<OrderItem> items;

        public InventoryReservedEvent(String orderId, List<OrderItem> items,
                                      String correlationId, String causationId) {
            super(EventTypes.INVENTORY_RESERVED, "/services/inventory-service", correlationId, causationId, 1);
            this.orderId = orderId;
            this.items = items;
        }
    }

    @Getter
    public static class InventoryReservationFailedEvent extends DomainEvent {
        private final String orderId;
        private final String reason;
        private final List<String> insufficientProductIds;

        public InventoryReservationFailedEvent(String orderId, String reason,
                                               List<String> insufficientProductIds,
                                               String correlationId, String causationId) {
            super(EventTypes.INVENTORY_RESERVATION_FAILED, "/services/inventory-service", correlationId, causationId, 1);
            this.orderId = orderId;
            this.reason = reason;
            this.insufficientProductIds = insufficientProductIds;
        }
    }

    @Getter
    public static class InventoryReleasedEvent extends DomainEvent {
        private final String orderId;
        private final List<OrderItem> items;

        public InventoryReleasedEvent(String orderId, List<OrderItem> items,
                                      String correlationId, String causationId) {
            super(EventTypes.INVENTORY_RELEASED, "/services/order-service", correlationId, causationId, 1);
            this.orderId = orderId;
            this.items = items;
        }
    }

    // ─── Notification Events ───────────────────────────────────────────────────

    @Getter
    public static class NotificationSendEvent extends DomainEvent {
        private final String customerId;
        private final String channel;   // email | sms | webhook
        private final String templateId;
        private final java.util.Map<String, Object> variables;

        public NotificationSendEvent(String customerId, String channel, String templateId,
                                     java.util.Map<String, Object> variables,
                                     String correlationId, String causationId) {
            super(EventTypes.NOTIFICATION_SEND, "/services/order-service", correlationId, causationId, 1);
            this.customerId = customerId;
            this.channel = channel;
            this.templateId = templateId;
            this.variables = variables;
        }
    }

    // ─── Value Objects ─────────────────────────────────────────────────────────

    @Getter
    public static class OrderItem {
        private final String productId;
        private final int quantity;
        private final BigDecimal unitPrice;

        public OrderItem(String productId, int quantity, BigDecimal unitPrice) {
            this.productId = productId;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }
    }

    @Getter
    public static class ShippingAddress {
        private final String street;
        private final String city;
        private final String country;
        private final String postalCode;

        public ShippingAddress(String street, String city, String country, String postalCode) {
            this.street = street;
            this.city = city;
            this.country = country;
            this.postalCode = postalCode;
        }
    }
}
