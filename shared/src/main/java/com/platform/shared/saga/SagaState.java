package com.platform.shared.saga;


import lombok.*;


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Saga State — persisted in Redis (serialized as JSON).
 * Represents the current state of an in-flight distributed transaction.
 *
 * Design: Saga state is stored in Redis with TTL. The orchestrator is stateless —
 * all context needed to resume a saga after a crash is in this record.
 *
 * If Redis is unavailable, in-flight sagas may be lost. Production systems
 * should use Redis Cluster with AOF persistence for durability.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SagaState {

    public enum Status {
        STARTED,
        RESERVING_INVENTORY,
        PROCESSING_PAYMENT,
        CONFIRMING,
        COMPLETED,
        COMPENSATING,
        COMPENSATED,
        FAILED,
        TIMED_OUT
    }

    private String correlationId;
    private String orderId;
    private String customerId;

    /** Full order snapshot — needed for compensation steps */
    private Object orderSnapshot;

    private Status status;
    private String currentStep;

    @Builder.Default
    private List<String> completedSteps = new ArrayList<>();

    private String paymentId;
    private String failureReason;

    private Instant startedAt;
    private Instant lastUpdatedAt;
    private Instant completedAt;
    private Instant failedAt;
    private Instant timeoutAt;

    public void addCompletedStep(String step) {
        this.completedSteps.add(step);
        this.lastUpdatedAt = Instant.now();
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED
                || status == Status.COMPENSATED
                || status == Status.FAILED
                || status == Status.TIMED_OUT;
    }

    public boolean hasCompletedStep(String step) {
        return completedSteps.contains(step);
    }

    public boolean isTimedOut() {
        return timeoutAt != null && Instant.now().isAfter(timeoutAt);
    }
}
