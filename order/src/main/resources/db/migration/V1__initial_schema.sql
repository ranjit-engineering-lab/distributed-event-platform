-- ============================================================================
-- V1__initial_schema.sql
-- Event-Driven Platform — PostgreSQL Schema
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ─── Orders (Write Model) ────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS orders (
  id               VARCHAR(50)   PRIMARY KEY,
  customer_id      VARCHAR(50)   NOT NULL,
  status           VARCHAR(50)   NOT NULL DEFAULT 'PENDING',
  items            JSONB         NOT NULL,
  total_amount     NUMERIC(12,2) NOT NULL,
  currency         VARCHAR(3)    NOT NULL DEFAULT 'USD',
  payment_method   VARCHAR(50)   NOT NULL,
  shipping_address JSONB,
  correlation_id   VARCHAR(36)   NOT NULL,
  version          BIGINT        NOT NULL DEFAULT 0,
  created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_customer_id    ON orders(customer_id);
CREATE INDEX idx_orders_status         ON orders(status);
CREATE INDEX idx_orders_correlation_id ON orders(correlation_id);
CREATE INDEX idx_orders_created_at     ON orders(created_at DESC);

-- ─── Order Events (Event Store) ───────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS order_events (
  id             VARCHAR(36)  PRIMARY KEY,
  order_id       VARCHAR(50)  NOT NULL REFERENCES orders(id),
  event_type     VARCHAR(100) NOT NULL,
  payload        TEXT         NOT NULL,
  sequence       INTEGER      NOT NULL,
  correlation_id VARCHAR(36),
  causation_id   VARCHAR(36),
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

  UNIQUE(order_id, sequence)
);

CREATE INDEX idx_order_events_order_id       ON order_events(order_id, sequence);
CREATE INDEX idx_order_events_type           ON order_events(event_type);
CREATE INDEX idx_order_events_correlation_id ON order_events(correlation_id);

-- ─── Outbox (Transactional Outbox Pattern) ────────────────────────────────────

CREATE TABLE IF NOT EXISTS outbox (
  id             VARCHAR(36)   PRIMARY KEY,
  aggregate_id   VARCHAR(100)  NOT NULL,
  aggregate_type VARCHAR(50)   NOT NULL,
  event_type     VARCHAR(100)  NOT NULL,
  topic          VARCHAR(200)  NOT NULL,
  payload        JSONB         NOT NULL,
  published_at   TIMESTAMPTZ,
  retry_count    INTEGER       NOT NULL DEFAULT 0,
  last_error     TEXT,
  next_retry_at  TIMESTAMPTZ,
  created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- This index is critical — outbox relay scans it on every poll cycle
CREATE INDEX idx_outbox_unpublished ON outbox(created_at ASC)
  WHERE published_at IS NULL AND retry_count < 5;
CREATE INDEX idx_outbox_aggregate   ON outbox(aggregate_id);

-- ─── Payments (Payment Service) ───────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS payments (
  id             VARCHAR(50)   PRIMARY KEY,
  order_id       VARCHAR(50)   NOT NULL UNIQUE,
  customer_id    VARCHAR(50)   NOT NULL,
  amount         NUMERIC(12,2) NOT NULL,
  currency       VARCHAR(3)    NOT NULL,
  payment_method VARCHAR(50)   NOT NULL,
  status         VARCHAR(50)   NOT NULL,
  failure_reason TEXT,
  gateway_ref    VARCHAR(200),
  created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_order_id    ON payments(order_id);
CREATE INDEX idx_payments_customer_id ON payments(customer_id);
CREATE INDEX idx_payments_status      ON payments(status);

-- ─── Refunds ──────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS refunds (
  id          VARCHAR(50)   PRIMARY KEY,
  payment_id  VARCHAR(50)   NOT NULL REFERENCES payments(id),
  order_id    VARCHAR(50)   NOT NULL,
  amount      NUMERIC(12,2) NOT NULL,
  currency    VARCHAR(3)    NOT NULL,
  status      VARCHAR(50)   NOT NULL DEFAULT 'COMPLETED',
  gateway_ref VARCHAR(200),
  created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_refunds_payment_id ON refunds(payment_id);

-- ─── Inventory ────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS inventory (
  id                 UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  product_id         VARCHAR(100) NOT NULL UNIQUE,
  sku                VARCHAR(100) NOT NULL,
  available_quantity INTEGER      NOT NULL DEFAULT 0 CHECK (available_quantity >= 0),
  reserved_quantity  INTEGER      NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
  reorder_point      INTEGER      NOT NULL DEFAULT 10,
  version            INTEGER      NOT NULL DEFAULT 1,
  created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inventory_product_id ON inventory(product_id);

CREATE TABLE IF NOT EXISTS inventory_reservations (
  id         UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
  order_id   VARCHAR(50) NOT NULL UNIQUE,
  items      JSONB       NOT NULL,
  status     VARCHAR(50) NOT NULL DEFAULT 'RESERVED',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inventory_reservations_order_id ON inventory_reservations(order_id);

-- ─── DLQ Messages ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS dlq_messages (
  id             VARCHAR(36)  PRIMARY KEY,
  original_topic VARCHAR(200) NOT NULL,
  reason         VARCHAR(100) NOT NULL,
  error_message  TEXT,
  original_value TEXT,
  status         VARCHAR(50)  NOT NULL DEFAULT 'PENDING_REVIEW',
  failed_at      TIMESTAMPTZ,
  received_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  replayed_at    TIMESTAMPTZ
);

CREATE INDEX idx_dlq_messages_status ON dlq_messages(status);
CREATE INDEX idx_dlq_messages_topic  ON dlq_messages(original_topic);

-- ─── Auto-update trigger ─────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION trigger_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY['orders','outbox','payments','inventory','inventory_reservations']
  LOOP
    EXECUTE format('
      DROP TRIGGER IF EXISTS set_updated_at_on_%I ON %I;
      CREATE TRIGGER set_updated_at_on_%I
      BEFORE UPDATE ON %I
      FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();
    ', t, t, t, t);
  END LOOP;
END;
$$;

-- ─── Sample Inventory Data ────────────────────────────────────────────────────

INSERT INTO inventory (product_id, sku, available_quantity, reorder_point)
VALUES
  ('prod_laptop_001',     'SKU-LAP-001', 100, 10),
  ('prod_mouse_001',      'SKU-MOU-001', 500, 50),
  ('prod_keyboard_001',   'SKU-KEY-001', 250, 25),
  ('prod_monitor_001',    'SKU-MON-001',  50,  5),
  ('prod_headphones_001', 'SKU-HDP-001', 200, 20)
ON CONFLICT (product_id) DO NOTHING;
