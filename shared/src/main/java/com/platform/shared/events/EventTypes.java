package com.platform.shared.events;

/**
 * Canonical event type constants.
 * All services MUST use these constants — never hardcode strings.
 * Changing a type here is a breaking change requiring consumer updates.
 */
public final class EventTypes {

    private EventTypes() {}

    // ── Order Domain ──────────────────────────────────────────────────────────
    public static final String ORDER_CREATED      = "orders.created";
    public static final String ORDER_CONFIRMED    = "orders.confirmed";
    public static final String ORDER_CANCELLED    = "orders.cancelled";
    public static final String ORDER_COMPENSATE   = "orders.compensate";

    // ── Payment Domain ────────────────────────────────────────────────────────
    public static final String PAYMENT_INITIATED  = "payments.initiated";
    public static final String PAYMENT_COMPLETED  = "payments.completed";
    public static final String PAYMENT_FAILED     = "payments.failed";
    public static final String PAYMENT_REFUNDED   = "payments.refunded";

    // ── Inventory Domain ──────────────────────────────────────────────────────
    public static final String INVENTORY_RESERVE_REQUESTED  = "inventory.reserve-requested";
    public static final String INVENTORY_RESERVED           = "inventory.reserved";
    public static final String INVENTORY_RESERVATION_FAILED = "inventory.reservation-failed";
    public static final String INVENTORY_RELEASED           = "inventory.released";

    // ── Notification Domain ───────────────────────────────────────────────────
    public static final String NOTIFICATION_SEND  = "notifications.send";

    // ── Saga Domain ───────────────────────────────────────────────────────────
    public static final String SAGA_STARTED      = "saga.started";
    public static final String SAGA_COMPLETED    = "saga.completed";
    public static final String SAGA_FAILED       = "saga.failed";
    public static final String SAGA_COMPENSATING = "saga.compensating";
    public static final String SAGA_COMPENSATED  = "saga.compensated";

    // ── Kafka Topics (same as event types for simplicity) ─────────────────────
    public static final String TOPIC_ORDERS_CREATED              = ORDER_CREATED;
    public static final String TOPIC_ORDERS_CONFIRMED            = ORDER_CONFIRMED;
    public static final String TOPIC_ORDERS_CANCELLED            = ORDER_CANCELLED;
    public static final String TOPIC_PAYMENTS_INITIATED          = PAYMENT_INITIATED;
    public static final String TOPIC_PAYMENTS_COMPLETED          = PAYMENT_COMPLETED;
    public static final String TOPIC_PAYMENTS_FAILED             = PAYMENT_FAILED;
    public static final String TOPIC_PAYMENTS_REFUNDED           = PAYMENT_REFUNDED;
    public static final String TOPIC_INVENTORY_RESERVE_REQUESTED = INVENTORY_RESERVE_REQUESTED;
    public static final String TOPIC_INVENTORY_RESERVED          = INVENTORY_RESERVED;
    public static final String TOPIC_INVENTORY_RESERVATION_FAILED= INVENTORY_RESERVATION_FAILED;
    public static final String TOPIC_INVENTORY_RELEASED          = INVENTORY_RELEASED;
    public static final String TOPIC_NOTIFICATIONS_SEND          = NOTIFICATION_SEND;
    public static final String TOPIC_SAGA_EVENTS                 = "saga.events";
    public static final String TOPIC_DLQ_ORDERS                  = "dlq.orders";
    public static final String TOPIC_DLQ_PAYMENTS                = "dlq.payments";
    public static final String TOPIC_DLQ_INVENTORY               = "dlq.inventory";
}
