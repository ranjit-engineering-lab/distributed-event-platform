package com.platform.shared.saga;

import com.platform.shared.events.Events.*;
import com.platform.shared.events.EventTypes;
import com.platform.shared.kafka.EventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Order Saga Orchestrator
 *
 * Central coordinator for the order processing distributed transaction.
 * Implements the Orchestration pattern (vs Choreography).
 *
 * Flow:
 *   OrderCreated → [reserve inventory] → [process payment] → [confirm order] → [notify]
 *
 * On any failure, compensation runs in reverse order of completed steps:
 *   PaymentFailed → [release inventory] → [cancel order] → [notify customer]
 *
 * This class is STATELESS — all saga state lives in SagaStateStore (Redis).
 * Multiple instances of the order-service can run concurrently safely.
 *
 * Why orchestration over choreography?
 * See ADR-001: docs/adr/001-saga-orchestration.md
 */
@Slf4j
@Service
public class OrderSagaOrchestrator {

    private static final String STEP_RESERVE_INVENTORY = "RESERVE_INVENTORY";
    private static final String STEP_PROCESS_PAYMENT   = "PROCESS_PAYMENT";
    private static final String STEP_CONFIRM_ORDER     = "CONFIRM_ORDER";
    private static final String STEP_SEND_NOTIFICATION = "SEND_NOTIFICATION";

    private final SagaStateStore stateStore;
    private final EventPublisher eventPublisher;
    private final long sagaTimeoutMs;

    // Metrics
    private final Counter sagasStarted;
    private final Counter sagasCompleted;
    private final Counter sagasCompensating;
    private final Counter sagasCompensated;
    private final Timer sagaDuration;

    public OrderSagaOrchestrator(
            SagaStateStore stateStore,
            EventPublisher eventPublisher,
            MeterRegistry meterRegistry,
            @Value("${saga.timeout-ms:300000}") long sagaTimeoutMs) {
        this.stateStore = stateStore;
        this.eventPublisher = eventPublisher;
        this.sagaTimeoutMs = sagaTimeoutMs;

        this.sagasStarted      = Counter.builder("saga.started").register(meterRegistry);
        this.sagasCompleted    = Counter.builder("saga.completed").register(meterRegistry);
        this.sagasCompensating = Counter.builder("saga.compensating").register(meterRegistry);
        this.sagasCompensated  = Counter.builder("saga.compensated").register(meterRegistry);
        this.sagaDuration      = Timer.builder("saga.duration").register(meterRegistry);
    }

    // ─── Saga Initiation ───────────────────────────────────────────────────────

    /**
     * Start a new order saga.
     * Called when an ORDER_CREATED event is consumed.
     */
    public void startSaga(OrderCreatedEvent event) {
        log.info("Starting order saga: correlationId={}, orderId={}",
                event.getCorrelationId(), event.getOrderId());

        SagaState state = SagaState.builder()
                .correlationId(event.getCorrelationId())
                .orderId(event.getOrderId())
                .customerId(event.getCustomerId())
                .orderSnapshot(event)
                .status(SagaState.Status.STARTED)
                .startedAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .timeoutAt(Instant.now().plusMillis(sagaTimeoutMs))
                .build();

        stateStore.save(state);
        sagasStarted.increment();

        // Kick off step 1: Reserve Inventory
        executeStep(state, STEP_RESERVE_INVENTORY, event);
    }

    // ─── Success Handlers ──────────────────────────────────────────────────────

    public void onInventoryReserved(InventoryReservedEvent event) {
        Optional<SagaState> stateOpt = loadAndValidate(event.getCorrelationId(),
                SagaState.Status.RESERVING_INVENTORY, EventTypes.INVENTORY_RESERVED);
        if (stateOpt.isEmpty()) return;

        SagaState state = stateOpt.get();
        state.addCompletedStep(STEP_RESERVE_INVENTORY);
        stateStore.save(state);

        executeStep(state, STEP_PROCESS_PAYMENT, event);
    }

    public void onPaymentCompleted(PaymentCompletedEvent event) {
        Optional<SagaState> stateOpt = loadAndValidate(event.getCorrelationId(),
                SagaState.Status.PROCESSING_PAYMENT, EventTypes.PAYMENT_COMPLETED);
        if (stateOpt.isEmpty()) return;

        SagaState state = stateOpt.get();
        state.setPaymentId(event.getPaymentId());
        state.addCompletedStep(STEP_PROCESS_PAYMENT);
        stateStore.save(state);

        executeStep(state, STEP_CONFIRM_ORDER, event);
    }

    public void onOrderConfirmed(OrderConfirmedEvent event) {
        Optional<SagaState> stateOpt = loadAndValidate(event.getCorrelationId(),
                SagaState.Status.CONFIRMING, EventTypes.ORDER_CONFIRMED);
        if (stateOpt.isEmpty()) return;

        SagaState state = stateOpt.get();
        state.addCompletedStep(STEP_CONFIRM_ORDER);
        stateStore.save(state);

        executeStep(state, STEP_SEND_NOTIFICATION, event);
    }

    // ─── Failure / Compensation Handlers ──────────────────────────────────────

    public void onInventoryReservationFailed(InventoryReservationFailedEvent event) {
        Optional<SagaState> stateOpt = stateStore.load(event.getCorrelationId());
        if (stateOpt.isEmpty()) { handleOrphanEvent(event.getCorrelationId(), event.getType()); return; }

        startCompensation(stateOpt.get(), event.getId(),
                "Inventory reservation failed: " + event.getReason());
    }

    public void onPaymentFailed(PaymentFailedEvent event) {
        Optional<SagaState> stateOpt = stateStore.load(event.getCorrelationId());
        if (stateOpt.isEmpty()) { handleOrphanEvent(event.getCorrelationId(), event.getType()); return; }

        startCompensation(stateOpt.get(), event.getId(),
                "Payment failed: " + event.getReason());
    }

    // ─── Step Execution ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void executeStep(SagaState state, String step, Object causationEvent) {
        String causationId = causationEvent instanceof com.platform.shared.events.DomainEvent e
                ? e.getId() : null;
        String correlationId = state.getCorrelationId();

        state.setCurrentStep(step);
        state.setLastUpdatedAt(Instant.now());

        switch (step) {
            case STEP_RESERVE_INVENTORY -> {
                state.setStatus(SagaState.Status.RESERVING_INVENTORY);
                stateStore.save(state);

                OrderCreatedEvent orderEvent = (OrderCreatedEvent) state.getOrderSnapshot();
                eventPublisher.publish(
                        EventTypes.TOPIC_INVENTORY_RESERVE_REQUESTED,
                        new InventoryReserveRequestedEvent(
                                state.getOrderId(),
                                orderEvent.getItems(),
                                correlationId, causationId
                        )
                );
            }
            case STEP_PROCESS_PAYMENT -> {
                state.setStatus(SagaState.Status.PROCESSING_PAYMENT);
                stateStore.save(state);

                OrderCreatedEvent orderEvent = (OrderCreatedEvent) state.getOrderSnapshot();
                eventPublisher.publish(
                        EventTypes.TOPIC_PAYMENTS_INITIATED,
                        new PaymentInitiatedEvent(
                                state.getOrderId(),
                                state.getCustomerId(),
                                orderEvent.getTotalAmount(),
                                orderEvent.getCurrency(),
                                orderEvent.getPaymentMethod(),
                                correlationId, causationId
                        )
                );
            }
            case STEP_CONFIRM_ORDER -> {
                state.setStatus(SagaState.Status.CONFIRMING);
                stateStore.save(state);

                eventPublisher.publish(
                        EventTypes.TOPIC_ORDERS_CONFIRMED,
                        new OrderConfirmedEvent(
                                state.getOrderId(),
                                state.getCustomerId(),
                                correlationId, causationId
                        )
                );
            }
            case STEP_SEND_NOTIFICATION -> {
                OrderCreatedEvent orderEvent = (OrderCreatedEvent) state.getOrderSnapshot();
                eventPublisher.publish(
                        EventTypes.TOPIC_NOTIFICATIONS_SEND,
                        new NotificationSendEvent(
                                state.getCustomerId(),
                                "email",
                                "order-confirmed",
                                Map.of(
                                        "orderId", state.getOrderId(),
                                        "totalAmount", orderEvent.getTotalAmount(),
                                        "currency", orderEvent.getCurrency()
                                ),
                                correlationId, causationId
                        )
                );
                completeSaga(state);
            }
            default -> throw new IllegalArgumentException("Unknown saga step: " + step);
        }

        log.info("Saga step executed: correlationId={}, step={}, status={}",
                correlationId, step, state.getStatus());
    }

    // ─── Compensation ──────────────────────────────────────────────────────────

    /**
     * Execute compensation in reverse order of completed steps.
     * Only compensates steps that were actually completed.
     */
    private void startCompensation(SagaState state, String causationEventId, String reason) {
        log.warn("Starting saga compensation: correlationId={}, orderId={}, completedSteps={}, reason={}",
                state.getCorrelationId(), state.getOrderId(), state.getCompletedSteps(), reason);

        state.setStatus(SagaState.Status.COMPENSATING);
        state.setFailureReason(reason);
        state.setFailedAt(Instant.now());
        stateStore.save(state);
        sagasCompensating.increment();

        String correlationId = state.getCorrelationId();
        List<String> stepsToCompensate = new java.util.ArrayList<>(state.getCompletedSteps());
        java.util.Collections.reverse(stepsToCompensate);

        OrderCreatedEvent orderEvent = (OrderCreatedEvent) state.getOrderSnapshot();

        for (String step : stepsToCompensate) {
            switch (step) {
                case STEP_RESERVE_INVENTORY -> {
                    log.info("Compensating RESERVE_INVENTORY: releasing stock for orderId={}", state.getOrderId());
                    eventPublisher.publish(
                            EventTypes.TOPIC_INVENTORY_RELEASED,
                            new InventoryReleasedEvent(
                                    state.getOrderId(),
                                    orderEvent.getItems(),
                                    correlationId, causationEventId
                            )
                    );
                }
                case STEP_PROCESS_PAYMENT -> {
                    log.info("Compensating PROCESS_PAYMENT: refunding paymentId={}", state.getPaymentId());
                    eventPublisher.publish(
                            EventTypes.TOPIC_PAYMENTS_REFUNDED,
                            new PaymentRefundedEvent(
                                    state.getOrderId(),
                                    state.getPaymentId(),
                                    orderEvent.getTotalAmount(),
                                    orderEvent.getCurrency(),
                                    correlationId, causationEventId
                            )
                    );
                }
            }
        }

        // Cancel the order
        eventPublisher.publish(
                EventTypes.TOPIC_ORDERS_CANCELLED,
                new OrderCancelledEvent(
                        state.getOrderId(), state.getCustomerId(), reason,
                        correlationId, causationEventId
                )
        );

        // Notify customer of cancellation
        eventPublisher.publish(
                EventTypes.TOPIC_NOTIFICATIONS_SEND,
                new NotificationSendEvent(
                        state.getCustomerId(), "email", "order-cancelled",
                        Map.of("orderId", state.getOrderId(), "reason", reason),
                        correlationId, causationEventId
                )
        );

        state.setStatus(SagaState.Status.COMPENSATED);
        stateStore.save(state);
        sagasCompensated.increment();

        // Keep state visible for 5 minutes for debugging, then expire
        stateStore.scheduleDelete(correlationId, Duration.ofMinutes(5));

        log.info("Saga compensated: correlationId={}, orderId={}",
                correlationId, state.getOrderId());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void completeSaga(SagaState state) {
        state.setStatus(SagaState.Status.COMPLETED);
        state.setCompletedAt(Instant.now());
        stateStore.save(state);

        long durationMs = Duration.between(state.getStartedAt(), state.getCompletedAt()).toMillis();
        sagasCompleted.increment();
        sagaDuration.record(Duration.ofMillis(durationMs));

        log.info("Saga COMPLETED: correlationId={}, orderId={}, durationMs={}",
                state.getCorrelationId(), state.getOrderId(), durationMs);

        stateStore.scheduleDelete(state.getCorrelationId(), Duration.ofMinutes(5));
    }

    private Optional<SagaState> loadAndValidate(String correlationId,
                                                 SagaState.Status expectedStatus,
                                                 String eventType) {
        Optional<SagaState> stateOpt = stateStore.load(correlationId);
        if (stateOpt.isEmpty()) {
            handleOrphanEvent(correlationId, eventType);
            return Optional.empty();
        }

        SagaState state = stateOpt.get();
        if (state.getStatus() != expectedStatus) {
            log.warn("Saga step out of sequence: correlationId={}, currentStatus={}, expectedStatus={}, eventType={}",
                    correlationId, state.getStatus(), expectedStatus, eventType);
            return Optional.empty();
        }

        if (state.isTimedOut()) {
            log.error("Saga timed out: correlationId={}, orderId={}", correlationId, state.getOrderId());
            state.setStatus(SagaState.Status.TIMED_OUT);
            stateStore.save(state);
            startCompensation(state, null, "Saga timed out");
            return Optional.empty();
        }

        return stateOpt;
    }

    private void handleOrphanEvent(String correlationId, String eventType) {
        log.warn("Received event for unknown/expired saga: correlationId={}, eventType={}",
                correlationId, eventType);
    }
}
