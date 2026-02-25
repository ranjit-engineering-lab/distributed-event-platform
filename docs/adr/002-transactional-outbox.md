# ADR-002: Transactional Outbox over Dual Write

**Status:** Accepted
**Date:** 2024-01-20

---

## Context

When a service processes a command (e.g., create an order), it must:
1. Persist domain state to PostgreSQL
2. Publish an event to Kafka

Both must succeed or neither should. This is the **dual-write problem**.

---

## Options Considered

### Option A: Direct Dual Write (naïve)

```javascript
await db.insert(order);        // Step 1
await kafka.publish(event);    // Step 2 — crash here = event lost forever
```

**Problem:** If the process crashes between step 1 and 2:
- DB has the order
- Kafka never got the event
- Downstream services never process this order
- Inconsistency is silent and permanent

### Option B: Event-First (Kafka before DB)

```javascript
await kafka.publish(event);  // Step 1
await db.insert(order);      // Step 2 — crash here = duplicate event
```

**Problem:** Kafka publish cannot be rolled back. If DB insert fails, Kafka has a phantom event. Harder to handle than Option A.

### Option C: Transactional Outbox ✅ (chosen)

```sql
BEGIN
  INSERT INTO orders (...)     -- domain state
  INSERT INTO outbox (event)   -- event record (same transaction)
COMMIT
```

A background relay reads unpublished outbox records and publishes to Kafka. On success, marks as published.

---

## Decision

**Use Transactional Outbox** as the sole mechanism for publishing events.

**Why:**
- Domain state + event record are atomic (PostgreSQL transaction)
- No event is ever lost (outbox persists until published)
- At-least-once delivery is guaranteed
- Kafka's idempotent producer + consumer deduplication handles duplicate delivery

**Trade-offs accepted:**
- Slight publish latency (relay polling interval: 1 second)
- Additional DB table and relay process
- Relay is a new failure point (mitigated: relay is stateless, auto-restarts)

**Production note:** The polling relay is correct but adds DB load. For high throughput, replace with a CDC relay (Debezium reading PostgreSQL WAL) — zero polling, sub-100ms latency, no DB impact.

---

## Implementation

See `shared/outbox/relay.js` for the polling relay.
See `appendToOutbox()` helper for usage inside transactions.
