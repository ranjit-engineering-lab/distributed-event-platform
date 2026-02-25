# ADR-001: Saga Orchestration over Choreography

**Status:** Accepted
**Date:** 2024-01-15
**Deciders:** Platform Architecture Team

---

## Context

We need a mechanism to coordinate a multi-step distributed transaction (order processing) across 4 independent services (Order, Payment, Inventory, Notification). The operation must:
- Complete fully (all steps succeed), OR
- Roll back completely (compensate all completed steps on failure)

Two primary patterns exist for this: **Choreography** and **Orchestration**.

---

## Options Considered

### Option A: Event Choreography

Each service reacts to events published by other services. No central coordinator.

```
Order Service     → publishes `orders.created`
Inventory Service ← listens for `orders.created` → publishes `inventory.reserved`
Payment Service   ← listens for `inventory.reserved` → publishes `payments.completed`
Order Service     ← listens for `payments.completed` → publishes `orders.confirmed`
```

**Pros:**
- Fully decoupled — services don't know about each other
- No single point of logic failure
- Easy to add new participants without modifying existing services

**Cons:**
- Flow logic is implicit and distributed across services — no single place to read the business process
- Compensation is complex: each service must independently decide when and how to compensate, based on which downstream events it observes
- Cyclic event dependencies make debugging extremely difficult ("event spaghetti")
- Tracing a failure requires reading logs from 4+ services and correlating by event ID
- Adding a new step (e.g., fraud check) requires modifying multiple existing services

### Option B: Saga Orchestration ✅ (chosen)

A central orchestrator (the Order Saga class) defines the full workflow and sends commands to participants.

```
Order Saga Orchestrator:
  1. Send INVENTORY_RESERVE_REQUESTED → await INVENTORY_RESERVED / INVENTORY_RESERVATION_FAILED
  2. Send PAYMENT_INITIATED → await PAYMENT_COMPLETED / PAYMENT_FAILED
  3. Send ORDER_CONFIRMED → await (fire and forget)
  4. Send NOTIFICATION_SEND → done
```

**Pros:**
- Entire business flow readable in one file (`shared/saga/order-saga.js`)
- Compensation logic is centralized and deterministic
- Easy to add/remove steps without touching participant services
- Single place to add timeouts, retry policies, logging
- Explicit saga state machine makes debugging tractable

**Cons:**
- Orchestrator becomes a central point of business logic (not failure — it's stateless)
- Services are coupled to the saga through event contracts
- Risk of orchestrator becoming a "distributed monolith" if used carelessly

---

## Decision

**Use Orchestration** for the order processing saga.

**Key implementation details that mitigate the cons:**
1. Orchestrator is **stateless** — all state lives in Redis (crash-safe, scalable)
2. Participants only respond to domain events — they don't know they're in a saga
3. Compensation is co-located with the step that created the need for it
4. OpenTelemetry tracing makes the full saga flow visible in Jaeger UI

---

## Consequences

- Order Saga class owns the complete order flow definition
- Each participant service can be developed and tested independently
- New saga participants can be added by registering a new step in the orchestrator
- Saga timeouts are handled centrally (not per-service)
- Future sagas (e.g., refund saga) can be added without changing existing services
