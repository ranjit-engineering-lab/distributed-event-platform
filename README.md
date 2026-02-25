# Event-Driven Microservices Platform — Java / Spring Boot

> Production-grade event-driven microservices built with **Java 21**, **Spring Boot 3.2**, **Apache Kafka**, **PostgreSQL**, and **Redis**.
> Implements: Saga Orchestration · Transactional Outbox · Idempotent Consumers · Dead Letter Queue · CQRS · Event Sourcing · Circuit Breaker · Distributed Tracing

---

## Architecture

```
                    ┌─────────────────────────────────────┐
                    │         REST API (Port 8081)        │
                    │         POST /api/orders            │
                    └──────────────────┬──────────────────┘
                                       │ @Transactional
                    ┌──────────────────▼───────────────────┐
                    │        Order Service (8081)          │
                    │  - OrderCommandService  [Write]      │
                    │  - OrderQueryService    [Read/Redis] │
                    │  - OrderEventStore      [ES log]     │
                    │  - OutboxRelay          [scheduler]  │
                    │  - OrderSagaOrchestrator             │
                    └──────────────────┬───────────────────┘
                                       │
                    ┌──────────────────▼───────────────────────┐
                    │         Apache Kafka                     │
                    │  (Idempotent producer, 6 partitions)     │
                    └──┬────────────────┬────────────────┬─────┘
                       │                │                │
              ┌────────▼───────┐ ┌──────▼──────┐  ┌──────▼───────────┐
              │ Payment (8082) │ │Inventory    │  │Notification(8084)│
              │ Idempotent     │ │(8083)       │  │Pure reactive     │
              │ Optimistic lock│ │Optimistic   │  │Strategy pattern  │
              └────────────────┘ └─────────────┘  └──────────────────┘
```

## Stack

| Component | Technology |
|---|---|
| Language | Java 21 (records, pattern matching, virtual threads ready) |
| Framework | Spring Boot 3.2 |
| Messaging | Apache Kafka (Spring Kafka) |
| Database | PostgreSQL 16 + Flyway migrations |
| Cache / Saga State | Redis 7 (Lettuce pool) |
| ORM | Spring Data JPA + Hibernate |
| Tracing | OpenTelemetry → Jaeger |
| Metrics | Micrometer → Prometheus → Grafana |
| Circuit Breaker | Resilience4j |
| Testing | JUnit 5 + Mockito + Testcontainers |
| Build | Maven 3.9 (multi-module) |

## Module Structure

```
event-driven-platform/
├── pom.xml                          ← Parent POM with BOM dependencies
├── shared/                          ← Library used by all services
│   └── src/main/java/com/platform/shared/
│       ├── events/
│       │   ├── DomainEvent.java     ← CloudEvents v1.0 base class
│       │   ├── Events.java          ← All domain event classes
│       │   └── EventTypes.java      ← Event type + topic constants
│       ├── kafka/
│       │   ├── EventPublisher.java  ← Idempotent producer + tracing + metrics
│       │   └── IdempotencyService.java ← Redis NX deduplication
│       ├── outbox/
│       │   ├── OutboxRecord.java    ← JPA entity
│       │   ├── OutboxService.java   ← append() for use in @Transactional
│       │   └── OutboxRelayService.java ← @Scheduled relay (SKIP LOCKED)
│       └── saga/
│           ├── SagaState.java       ← State object (serialized to Redis)
│           ├── SagaStateStore.java  ← Redis persistence
│           └── OrderSagaOrchestrator.java ← Full saga: steps + compensation
│
├── order-service/                   ← Port 8081 — Saga initiator, CQRS
│   └── src/main/java/com/platform/order/
│       ├── OrderServiceApplication.java
│       ├── domain/
│       │   ├── Order.java           ← JPA entity (write model)
│       │   ├── OrderCommandService.java ← Create/update with outbox
│       │   ├── OrderQueryService.java   ← Redis read model (DB fallback)
│       │   └── OrderEventStore.java ← Append-only event log
│       ├── api/OrderController.java ← REST endpoints (CQRS split)
│       ├── saga/OrderSagaConsumer.java ← Kafka listener routing
│       └── config/OrderServiceConfig.java ← Kafka, Redis, Resilience4j
│
├── payment-service/                 ← Port 8082 — Idempotent payments
├── inventory-service/               ← Port 8083 — Optimistic locking
├── notification-service/            ← Port 8084 — Reactive, strategy pattern
├── docker-compose.yml               ← Full stack
└── docs/adr/                        ← Architecture Decision Records
```

## Patterns Implemented

| Pattern | Location | Key Detail |
|---|---|---|
| **Saga Orchestration** | `shared/saga/OrderSagaOrchestrator.java` | Stateless orchestrator, state in Redis |
| **Transactional Outbox** | `shared/outbox/` | `SELECT FOR UPDATE SKIP LOCKED` |
| **Idempotent Consumers** | `shared/kafka/IdempotencyService.java` | Redis SET NX with 24h TTL |
| **Circuit Breaker** | Resilience4j config in `application.yml` | Per-service breaker config |
| **CQRS** | `order-service/domain/` | Write → PostgreSQL, Read → Redis |
| **Event Sourcing** | `order-service/domain/OrderEventStore.java` | Append-only, replay method |
| **Optimistic Locking** | `inventory-service/` | JPA `@Version` on InventoryItem |
| **DLQ** | Kafka error handler + `DefaultErrorHandler` | Exponential backoff + DLQ routing |
| **Distributed Tracing** | OpenTelemetry + Jaeger | Headers propagated via Kafka headers |

## Quick Start

```bash
# Prerequisites: Java 21, Maven 3.9, Docker

# 1. Clone and build
git clone https://github.com/ranjit-engineering-lab/distributed-event-platform
cd distributed-event-platform
mvn clean install -DskipTests

# 2. Start infrastructure
docker-compose up -d zookeeper kafka kafka-init postgres redis jaeger prometheus grafana

# 3. Start services (separate terminals)
mvn spring-boot:run -pl order-service
mvn spring-boot:run -pl payment-service
mvn spring-boot:run -pl inventory-service
mvn spring-boot:run -pl notification-service

# 4. Create an order
curl -X POST http://localhost:8081/api/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": "cust_001",
    "items": [{"productId": "prod_laptop_001", "quantity": 1, "unitPrice": 999.99}],
    "paymentMethod": "CREDIT_CARD",
    "shippingAddress": {"street": "123 Main St", "city": "NY", "country": "US", "postalCode": "10001"}
  }'
```

## Running Tests

```bash
# Unit tests only (no Docker needed)
mvn test -pl order-service -Dtest=OrderSagaOrchestratorTest

# Integration tests (requires Docker)
mvn test -pl order-service -Dtest=OrderSagaIntegrationTest

# All tests
mvn test
```

## Observability

| Tool | URL | Purpose |
|---|---|---|
| Jaeger UI | http://localhost:16686 | Distributed trace visualization |
| Grafana | http://localhost:3000 | Metrics dashboards (admin/admin) |
| Kafka UI | http://localhost:8080 | Topic/consumer group inspection |
| Prometheus | http://localhost:9090 | Raw metrics scraping |
| Order Service | http://localhost:8081/actuator | Health, metrics, Prometheus endpoint |

## API Reference

```
# Write endpoints
POST   /api/orders                    Create order (initiates saga)
PATCH  /api/orders/{id}/cancel        Request cancellation

# Read endpoints (CQRS — served from Redis)
GET    /api/orders/{id}               Get order (Redis → DB fallback)
GET    /api/orders/{id}/events        Full event history (event sourcing)
GET    /api/orders/{id}/saga          Current saga state (debug)
GET    /api/orders/customer/{id}      Orders by customer

# Observability
GET    /actuator/health               Liveness + readiness
GET    /actuator/prometheus           Prometheus metrics
```

## Architecture Decision Records

- [ADR-001: Saga Orchestration over Choreography](docs/adr/001-saga-orchestration.md)
- [ADR-002: Transactional Outbox over Dual Write](docs/adr/002-transactional-outbox.md)
