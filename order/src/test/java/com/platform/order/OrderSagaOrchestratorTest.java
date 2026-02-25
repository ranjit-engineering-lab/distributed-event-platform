package com.platform.order;

import com.platform.shared.events.EventTypes;
import com.platform.shared.events.Events.*;
import com.platform.shared.kafka.EventPublisher;
import com.platform.shared.saga.OrderSagaOrchestrator;
import com.platform.shared.saga.SagaState;
import com.platform.shared.saga.SagaStateStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests — OrderSagaOrchestrator
 *
 * All infrastructure (Kafka, Redis) is mocked.
 * Tests saga state machine logic in isolation.
 */
@ExtendWith(MockitoExtension.class)
class OrderSagaOrchestratorTest {

    @Mock SagaStateStore stateStore;
    @Mock EventPublisher eventPublisher;

    OrderSagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new OrderSagaOrchestrator(
                stateStore, eventPublisher, new SimpleMeterRegistry(), 300_000L);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private OrderCreatedEvent buildOrderEvent() {
        return new OrderCreatedEvent(
                "ord_test_001",
                "cust_test_001",
                List.of(new OrderItem("prod_1", 2, new BigDecimal("49.99"))),
                new BigDecimal("99.98"),
                "USD",
                "CREDIT_CARD",
                new ShippingAddress("1 Main St", "NY", "US", "10001"),
                UUID.randomUUID().toString()
        );
    }

    private SagaState buildStateAtStep(OrderCreatedEvent orderEvent, SagaState.Status status,
                                        List<String> completedSteps) {
        SagaState state = SagaState.builder()
                .correlationId(orderEvent.getCorrelationId())
                .orderId(orderEvent.getOrderId())
                .customerId(orderEvent.getCustomerId())
                .orderSnapshot(orderEvent)
                .status(status)
                .completedSteps(new java.util.ArrayList<>(completedSteps))
                .startedAt(java.time.Instant.now().minusSeconds(10))
                .lastUpdatedAt(java.time.Instant.now())
                .timeoutAt(java.time.Instant.now().plusSeconds(300))
                .build();
        return state;
    }

    // ─── startSaga Tests ──────────────────────────────────────────────────────

    @Test
    @DisplayName("startSaga — saves state and publishes INVENTORY_RESERVE_REQUESTED")
    void startSaga_shouldSaveStateAndPublishInventoryReservation() {
        OrderCreatedEvent event = buildOrderEvent();

        orchestrator.startSaga(event);

        // Verify state was saved
        ArgumentCaptor<SagaState> stateCaptor = ArgumentCaptor.forClass(SagaState.class);
        verify(stateStore, atLeastOnce()).save(stateCaptor.capture());

        SagaState savedState = stateCaptor.getAllValues().stream()
                .filter(s -> s.getStatus() == SagaState.Status.RESERVING_INVENTORY)
                .findFirst().orElseThrow();
        assertThat(savedState.getOrderId()).isEqualTo("ord_test_001");
        assertThat(savedState.getCorrelationId()).isEqualTo(event.getCorrelationId());

        // Verify INVENTORY_RESERVE_REQUESTED was published
        verify(eventPublisher).publish(
                eq(EventTypes.TOPIC_INVENTORY_RESERVE_REQUESTED),
                argThat(e -> e instanceof InventoryReserveRequestedEvent &&
                        ((InventoryReserveRequestedEvent) e).getOrderId().equals("ord_test_001"))
        );
    }

    // ─── onInventoryReserved Tests ────────────────────────────────────────────

    @Test
    @DisplayName("onInventoryReserved — advances to PROCESSING_PAYMENT and publishes PaymentInitiated")
    void onInventoryReserved_shouldAdvanceToPayment() {
        OrderCreatedEvent orderEvent = buildOrderEvent();
        SagaState state = buildStateAtStep(orderEvent, SagaState.Status.RESERVING_INVENTORY, List.of());

        when(stateStore.load(orderEvent.getCorrelationId())).thenReturn(Optional.of(state));

        InventoryReservedEvent inventoryEvent = new InventoryReservedEvent(
                "ord_test_001", orderEvent.getItems(),
                orderEvent.getCorrelationId(), orderEvent.getId());

        orchestrator.onInventoryReserved(inventoryEvent);

        ArgumentCaptor<SagaState> stateCaptor = ArgumentCaptor.forClass(SagaState.class);
        verify(stateStore, atLeastOnce()).save(stateCaptor.capture());
        SagaState finalState = stateCaptor.getValue();
        assertThat(finalState.getCompletedSteps()).contains("RESERVE_INVENTORY");
        assertThat(finalState.getStatus()).isEqualTo(SagaState.Status.PROCESSING_PAYMENT);

        verify(eventPublisher).publish(
                eq(EventTypes.TOPIC_PAYMENTS_INITIATED),
                argThat(e -> e instanceof PaymentInitiatedEvent &&
                        ((PaymentInitiatedEvent) e).getAmount().compareTo(new BigDecimal("99.98")) == 0)
        );
    }

    @Test
    @DisplayName("onInventoryReserved — orphan event (no saga state) is handled gracefully")
    void onInventoryReserved_orphanEvent_shouldBeHandledGracefully() {
        when(stateStore.load(anyString())).thenReturn(Optional.empty());

        InventoryReservedEvent event = new InventoryReservedEvent(
                "ord_unknown", List.of(), "corr_unknown", "evt_unknown");

        assertThatCode(() -> orchestrator.onInventoryReserved(event)).doesNotThrowAnyException();
        verify(eventPublisher, never()).publish(anyString(), any());
    }

    // ─── Compensation Tests ───────────────────────────────────────────────────

    @Test
    @DisplayName("onInventoryReservationFailed — triggers full compensation (no completed steps)")
    void onInventoryReservationFailed_shouldCancelOrderWithNoCompensation() {
        OrderCreatedEvent orderEvent = buildOrderEvent();
        SagaState state = buildStateAtStep(orderEvent, SagaState.Status.RESERVING_INVENTORY, List.of());

        when(stateStore.load(orderEvent.getCorrelationId())).thenReturn(Optional.of(state));

        orchestrator.onInventoryReservationFailed(new InventoryReservationFailedEvent(
                "ord_test_001", "Out of stock", List.of("prod_1"),
                orderEvent.getCorrelationId(), "evt_fail"));

        ArgumentCaptor<SagaState> stateCaptor = ArgumentCaptor.forClass(SagaState.class);
        verify(stateStore, atLeastOnce()).save(stateCaptor.capture());
        SagaState finalState = stateCaptor.getAllValues().stream()
                .filter(s -> s.getStatus() == SagaState.Status.COMPENSATED)
                .findFirst().orElseThrow();

        assertThat(finalState.getFailureReason()).contains("Out of stock");

        // Should publish ORDER_CANCELLED and NOTIFICATION_SEND, but NOT INVENTORY_RELEASED
        // (RESERVE_INVENTORY was never completed)
        verify(eventPublisher).publish(eq(EventTypes.TOPIC_ORDERS_CANCELLED), any());
        verify(eventPublisher).publish(eq(EventTypes.TOPIC_NOTIFICATIONS_SEND), any());
        verify(eventPublisher, never()).publish(eq(EventTypes.TOPIC_INVENTORY_RELEASED), any());
    }

    @Test
    @DisplayName("onPaymentFailed — compensates RESERVE_INVENTORY step (publishes INVENTORY_RELEASED)")
    void onPaymentFailed_shouldReleaseInventory() {
        OrderCreatedEvent orderEvent = buildOrderEvent();
        SagaState state = buildStateAtStep(orderEvent, SagaState.Status.PROCESSING_PAYMENT,
                List.of("RESERVE_INVENTORY")); // Inventory step was completed

        when(stateStore.load(orderEvent.getCorrelationId())).thenReturn(Optional.of(state));

        orchestrator.onPaymentFailed(new PaymentFailedEvent(
                "ord_test_001", "Declined",
                orderEvent.getCorrelationId(), "evt_pay_fail"));

        // Should release inventory
        verify(eventPublisher).publish(eq(EventTypes.TOPIC_INVENTORY_RELEASED), any());
        // Should cancel order
        verify(eventPublisher).publish(eq(EventTypes.TOPIC_ORDERS_CANCELLED), any());
        // Should NOT attempt refund (payment never completed)
        verify(eventPublisher, never()).publish(eq(EventTypes.TOPIC_PAYMENTS_REFUNDED), any());
    }

    @Test
    @DisplayName("Full compensation — all steps completed before failure → release + refund")
    void fullCompensation_withAllStepsCompleted_shouldRefundAndRelease() {
        OrderCreatedEvent orderEvent = buildOrderEvent();
        SagaState state = buildStateAtStep(orderEvent, SagaState.Status.CONFIRMING,
                List.of("RESERVE_INVENTORY", "PROCESS_PAYMENT"));
        state.setPaymentId("pay_test_001");

        when(stateStore.load(orderEvent.getCorrelationId())).thenReturn(Optional.of(state));

        // Simulate a failure after both steps completed
        orchestrator.onPaymentFailed(new PaymentFailedEvent(
                "ord_test_001", "Gateway error",
                orderEvent.getCorrelationId(), "evt_fail"));

        // Both compensation steps should run
        verify(eventPublisher).publish(eq(EventTypes.TOPIC_PAYMENTS_REFUNDED), any());
        verify(eventPublisher).publish(eq(EventTypes.TOPIC_INVENTORY_RELEASED), any());
        verify(eventPublisher).publish(eq(EventTypes.TOPIC_ORDERS_CANCELLED), any());
        verify(eventPublisher).publish(eq(EventTypes.TOPIC_NOTIFICATIONS_SEND), any());
    }

    // ─── Step Out of Sequence ─────────────────────────────────────────────────

    @Test
    @DisplayName("Out-of-sequence event — saga in wrong status is ignored")
    void outOfSequenceEvent_shouldBeIgnored() {
        OrderCreatedEvent orderEvent = buildOrderEvent();
        // Saga is at RESERVING_INVENTORY but we receive PAYMENT_COMPLETED
        SagaState state = buildStateAtStep(orderEvent, SagaState.Status.RESERVING_INVENTORY, List.of());

        when(stateStore.load(orderEvent.getCorrelationId())).thenReturn(Optional.of(state));

        orchestrator.onPaymentCompleted(new PaymentCompletedEvent(
                "ord_test_001", "pay_001", new BigDecimal("99.98"), "USD",
                orderEvent.getCorrelationId(), "evt_pay"));

        // Should NOT advance saga (wrong state)
        verify(eventPublisher, never()).publish(eq(EventTypes.TOPIC_ORDERS_CONFIRMED), any());
    }
}
