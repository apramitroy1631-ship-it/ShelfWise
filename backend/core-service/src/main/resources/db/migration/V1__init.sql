-- =====================================================================
-- ShelfWise core service: initial schema (V1)
-- Every rule tagged Dnn comes from docs/data-findings.md; DLnn from docs/decision-log.md.
-- The notification service owns its own tables in its own migrations (DL-08, DL-09).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Stores and users
-- ---------------------------------------------------------------------
CREATE TABLE stores (
    id                    text PRIMARY KEY,                 -- 'S01' (natural key from stores.csv)
    name                  text NOT NULL,
    city                  text NOT NULL,
    state                 text NOT NULL,
    opened_on             date NOT NULL,
    main_contact_user_id  text,                             -- label only, no extra rights (D26); FK added below
    created_at            timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id                    text PRIMARY KEY,                 -- 'U01'
    name                  text NOT NULL,
    email                 text NOT NULL,
    role                  text NOT NULL CHECK (role IN ('ADMIN', 'STORE_MANAGER', 'FINANCE_APPROVER')),
    store_id              text REFERENCES stores (id),
    active                boolean NOT NULL DEFAULT true,    -- inactive users cannot sign in or approve (D20, D27)
    password_hash         text,                             -- BCrypt, never plain text (D25)
    must_change_password  boolean NOT NULL DEFAULT false,   -- honoured only when the remote-config switch is on (D25)
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint NOT NULL DEFAULT 0,
    -- a store manager belongs to exactly one store; head-office roles to none (D26)
    CONSTRAINT users_store_matches_role CHECK ((role = 'STORE_MANAGER') = (store_id IS NOT NULL))
);
CREATE UNIQUE INDEX users_email_unique ON users (lower(email));

ALTER TABLE stores
    ADD CONSTRAINT stores_main_contact_fk FOREIGN KEY (main_contact_user_id) REFERENCES users (id);

-- Refresh tokens are stored hashed so they can be revoked (logout, deactivation) (DL-05, D20).
CREATE TABLE refresh_tokens (
    id           uuid PRIMARY KEY,
    user_id      text NOT NULL REFERENCES users (id),
    token_hash   text NOT NULL UNIQUE,
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user ON refresh_tokens (user_id);

-- ---------------------------------------------------------------------
-- Suppliers and products
-- ---------------------------------------------------------------------
CREATE TABLE suppliers (
    id            text PRIMARY KEY,                         -- 'SUP01'; name defaults to 'Supplier SUP01' (D24)
    name          text NOT NULL,
    contact_name  text,
    phone         text,
    email         text,
    active        boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);

CREATE TABLE products (
    sku             text PRIMARY KEY,                       -- case-sensitive: 'SW-HOM-00033' and 'sw-hom-00033' are different products (D1)
    name            text NOT NULL,
    category        text NOT NULL,
    price_inr       numeric(12, 2) CHECK (price_inr > 0),   -- may be empty for imported rows only (D2)
    cost_price_inr  numeric(12, 2) NOT NULL CHECK (cost_price_inr >= 0),
    gst_rate        numeric(5, 2) NOT NULL CHECK (gst_rate >= 0),
    supplier_id     text NOT NULL REFERENCES suppliers (id), -- every product has a supplier (D24)
    status          text NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),  -- archived = no new orders or POs (D3)
    reorder_level   integer NOT NULL CHECK (reorder_level >= 0),
    catalogued_on   date NOT NULL,                          -- "added to the catalogue on" (D30)
    source          text NOT NULL CHECK (source IN ('IMPORT', 'APP')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,              -- optimistic locking (DL-05/D31 pattern)
    -- products created in the app must have a price; only imported ones may be empty (D2)
    CONSTRAINT products_price_required_for_app CHECK (price_inr IS NOT NULL OR source = 'IMPORT')
);
CREATE INDEX products_supplier ON products (supplier_id);
CREATE INDEX products_category ON products (category);

-- ---------------------------------------------------------------------
-- Inventory and stock history
-- ---------------------------------------------------------------------
CREATE TABLE inventory (
    store_id         text NOT NULL REFERENCES stores (id),
    sku              text NOT NULL REFERENCES products (sku),
    quantity         integer NOT NULL CHECK (quantity >= 0), -- stock can never go below 0 (D5, D29)
    reorder_level    integer NOT NULL CHECK (reorder_level >= 0),
    last_counted_at  timestamptz,                           -- all formats normalised to an exact moment (D6)
    recount_needed   boolean NOT NULL DEFAULT false,        -- set by import corrections (D5); cleared by a counted adjustment
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (store_id, sku)
);

-- Every change to stock is recorded with its reason (brief: "adjustments with a reason, and a history").
CREATE TABLE stock_movements (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id        text NOT NULL,
    sku             text NOT NULL,
    delta           integer NOT NULL,
    quantity_after  integer NOT NULL CHECK (quantity_after >= 0),
    reason          text NOT NULL CHECK (reason IN (
                        'ORDER_CREATED', 'ORDER_CANCELLED', 'PO_RECEIPT',
                        'ADJUSTMENT_DAMAGED', 'ADJUSTMENT_RECOUNT', 'ADJUSTMENT_OTHER',
                        'IMPORT_CORRECTION')),
    note            text,
    reference_type  text,                                   -- 'ORDER' | 'PURCHASE_ORDER' | null
    reference_id    text,
    actor_user_id   text REFERENCES users (id),             -- null = system (import, jobs)
    created_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (store_id, sku) REFERENCES inventory (store_id, sku)
);
CREATE INDEX stock_movements_item ON stock_movements (store_id, sku, created_at DESC);

-- ---------------------------------------------------------------------
-- Customer orders
-- ---------------------------------------------------------------------
CREATE TABLE customer_orders (
    id               text PRIMARY KEY,                      -- 'ORD-000001'; unique, so a duplicate can never get in (D8)
    store_id         text NOT NULL REFERENCES stores (id),
    placed_by        text NOT NULL REFERENCES users (id),
    status           text NOT NULL CHECK (status IN ('PENDING', 'PACKED', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    customer_name    text NOT NULL,                         -- customer kept on the order only (D16)
    customer_phone   text NOT NULL,                         -- masked in lists, exports and logs (D16)
    total_amount     numeric(12, 2) NOT NULL,
    created_at       timestamptz NOT NULL,                  -- order time; imported formats normalised to IST rules (D9)
    updated_at       timestamptz NOT NULL DEFAULT now(),
    source           text NOT NULL CHECK (source IN ('IMPORT', 'APP')),
    idempotency_key  text UNIQUE,                           -- a retried "create order" request counts once (D8 / DL-05)
    -- admin review queue (D10): flagged orders are excluded from revenue until reviewed
    review_status    text CHECK (review_status IN ('NEEDS_REVIEW', 'CORRECTED', 'REJECTED')),
    review_note      text,
    reviewed_by      text REFERENCES users (id),
    reviewed_at      timestamptz,
    version          bigint NOT NULL DEFAULT 0
);
CREATE INDEX customer_orders_store_created ON customer_orders (store_id, created_at DESC);
CREATE INDEX customer_orders_status ON customer_orders (status);
CREATE INDEX customer_orders_review ON customer_orders (review_status) WHERE review_status IS NOT NULL;

CREATE TABLE order_lines (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      text NOT NULL REFERENCES customer_orders (id),
    sku_text      text NOT NULL,                            -- SKU exactly as received (D12, D13)
    product_sku   text REFERENCES products (sku),           -- null when the product is unknown (D12)
    qty           integer NOT NULL,
    unit_price    numeric(12, 2) NOT NULL,                  -- price at the time of sale, never rewritten (D15)
    line_total    numeric(14, 2) GENERATED ALWAYS AS (qty * unit_price) STORED,
    price_source  text NOT NULL DEFAULT 'RECORDED'
                  CHECK (price_source IN ('RECORDED', 'SALES_HISTORY', 'BILL_TOTAL', 'ADMIN')),  -- D11
    imported      boolean NOT NULL DEFAULT false,
    -- new lines need a positive quantity; imported negative lines are kept as received and flagged (D10)
    CONSTRAINT order_lines_qty_positive CHECK (qty > 0 OR imported),
    CONSTRAINT order_lines_one_per_product UNIQUE (order_id, sku_text)   -- repeated lines are merged (D14)
);
CREATE INDEX order_lines_product ON order_lines (product_sku);

-- ---------------------------------------------------------------------
-- Purchase orders, approvals, receipts, invoices
-- ---------------------------------------------------------------------
CREATE TABLE purchase_orders (
    id                   text PRIMARY KEY,                  -- 'PO-0001'
    store_id             text NOT NULL REFERENCES stores (id),
    supplier_id          text NOT NULL REFERENCES suppliers (id),  -- one supplier per PO (D24)
    created_by           text NOT NULL REFERENCES users (id),
    status               text NOT NULL CHECK (status IN (
                             'DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED',
                             'REJECTED', 'EXPIRED', 'CLOSED')),  -- receiving statuses follow quantities (D21)
    total_amount         numeric(14, 2) NOT NULL CHECK (total_amount >= 0),
    created_at           timestamptz NOT NULL,
    submitted_at         timestamptz,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    source               text NOT NULL CHECK (source IN ('IMPORT', 'APP')),
    -- historical rule breaks, flagged not rewritten (D17-D20)
    compliance_flag      text CHECK (compliance_flag IN (
                             'SINGLE_APPROVAL', 'INVALID_APPROVER', 'SELF_APPROVAL')),
    requires_reapproval  boolean NOT NULL DEFAULT false,    -- no receiving until re-approved (D17-D19 progress rule)
    closed_reason        text,                              -- manager closes a stuck PO (D22)
    closed_by            text REFERENCES users (id),
    closed_at            timestamptz,
    version              bigint NOT NULL DEFAULT 0          -- optimistic check for concurrent approvals (D31)
);
CREATE INDEX purchase_orders_store_status ON purchase_orders (store_id, status);

CREATE TABLE po_lines (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    po_id         text NOT NULL REFERENCES purchase_orders (id),
    sku           text NOT NULL REFERENCES products (sku),
    qty_ordered   integer NOT NULL CHECK (qty_ordered > 0),
    unit_cost     numeric(12, 2) NOT NULL CHECK (unit_cost >= 0),
    qty_received  integer NOT NULL DEFAULT 0 CHECK (qty_received >= 0),
    CONSTRAINT po_lines_not_over_received CHECK (qty_received <= qty_ordered),
    CONSTRAINT po_lines_one_per_product UNIQUE (po_id, sku)
);

CREATE TABLE po_approvals (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    po_id        text NOT NULL REFERENCES purchase_orders (id),
    approver_id  text NOT NULL REFERENCES users (id),
    decision     text NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    decided_at   timestamptz NOT NULL,                      -- order of approvals comes from time, not list position (D31)
    counts       boolean NOT NULL DEFAULT true,             -- store-manager / self approvals never count (D18, D19)
    step         smallint CHECK (step IN (1, 2)),           -- 1st or 2nd valid approval; null when it doesn't count
    CONSTRAINT po_approvals_once_per_approver UNIQUE (po_id, approver_id),  -- same person can't approve twice (D31)
    CONSTRAINT po_approvals_step_only_if_counts CHECK (counts OR step IS NULL)
);
-- at most one valid 1st and one valid 2nd approval per PO, even under concurrent clicks (D31)
CREATE UNIQUE INDEX po_approvals_one_per_step ON po_approvals (po_id, step) WHERE counts;

-- Goods can be received in parts (brief); each delivery is recorded.
CREATE TABLE po_receipts (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    po_id        text NOT NULL REFERENCES purchase_orders (id),
    sku          text NOT NULL REFERENCES products (sku),
    qty          integer NOT NULL CHECK (qty > 0),
    received_by  text NOT NULL REFERENCES users (id),
    received_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX po_receipts_po ON po_receipts (po_id);

-- Supplier invoices: one current file per PO, older versions kept (DL-04).
CREATE TABLE invoices (
    id            uuid PRIMARY KEY,
    po_id         text NOT NULL REFERENCES purchase_orders (id),
    object_key    text NOT NULL UNIQUE,                     -- location in MinIO / R2
    file_name     text NOT NULL,
    content_type  text NOT NULL CHECK (content_type IN ('application/pdf', 'image/png', 'image/jpeg')),
    size_bytes    bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 20971520),  -- 20 MB limit
    sha256        text NOT NULL,
    is_current    boolean NOT NULL DEFAULT true,
    uploaded_by   text NOT NULL REFERENCES users (id),
    uploaded_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX invoices_one_current_per_po ON invoices (po_id) WHERE is_current;

-- ---------------------------------------------------------------------
-- Events, settings, audit
-- ---------------------------------------------------------------------
-- Transactional outbox: written in the same transaction as the change, then published to RabbitMQ (DL-03).
CREATE TABLE outbox_events (
    id              uuid PRIMARY KEY,
    aggregate_type  text NOT NULL,                          -- 'INVENTORY' | 'PURCHASE_ORDER' | 'ORDER' ...
    aggregate_id    text NOT NULL,
    event_type      text NOT NULL,                          -- e.g. 'StockLevelChanged', 'PurchaseOrderDecided'
    payload         jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz
);
CREATE INDEX outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

-- Remote configuration (DL-06): changed by admins, read by services and apps without a release.
CREATE TABLE app_settings (
    key          text PRIMARY KEY,
    value        jsonb NOT NULL,
    description  text NOT NULL,
    updated_by   text REFERENCES users (id),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

INSERT INTO app_settings (key, value, description) VALUES
    ('lowStock.defaultReorderLevel',     '10',    'Reorder level used when a product has none'),
    ('purchaseOrders.draftExpiryDays',   '14',    'Drafts untouched longer than this expire nightly (D22)'),
    ('purchaseOrders.stuckAfterDays',    '30',    'Approved POs waiting longer than this for goods are flagged stuck (D22)'),
    ('purchaseOrders.secondApprovalAboveInr', '50000', 'POs above this total need two different finance approvers'),
    ('orders.stuckAfterDays',            '7',     'Open orders older than this are flagged stuck (D32)'),
    ('auth.forcePasswordChangeOnFirstLogin', 'false', 'Off for the shared demo environment (D25)'),
    ('ui.maintenanceBanner',             'null',  'Text shown at the top of web and mobile; null hides it'),
    ('features.adminReviewQueue',        'true',  'Admin review screen for flagged imported records (D10)');

-- Who changed what and when: archive/unarchive (D3), prices (D2), reviews (D10), PO close (D22), settings (DL-06).
CREATE TABLE audit_log (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id  text REFERENCES users (id),               -- null = system
    action         text NOT NULL,
    entity_type    text NOT NULL,
    entity_id      text NOT NULL,
    before_value   jsonb,
    after_value    jsonb,
    reason         text,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_entity ON audit_log (entity_type, entity_id, created_at DESC);

-- ---------------------------------------------------------------------
-- Import bookkeeping: keep the original, report every decision
-- ---------------------------------------------------------------------
CREATE TABLE import_runs (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mode         text NOT NULL CHECK (mode IN ('INSERT_ONLY', 'RESET')),  -- D28
    status       text NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    started_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    summary      jsonb                                      -- added / skipped / rejected counts per file
);

-- Every record exactly as it was in the file ("keep the original").
CREATE TABLE raw_import_records (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id       bigint NOT NULL REFERENCES import_runs (id),
    source_file  text NOT NULL,
    position     integer NOT NULL,                          -- CSV line number or JSON array index
    natural_key  text,
    payload      jsonb NOT NULL,
    CONSTRAINT raw_import_records_unique_position UNIQUE (run_id, source_file, position)
);

-- One row per decision applied to a record: the basis of the import report and the admin review queue.
CREATE TABLE import_issues (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id          bigint NOT NULL REFERENCES import_runs (id),
    decision_code   text NOT NULL,                          -- 'D1' ... 'D32'
    outcome         text NOT NULL CHECK (outcome IN ('INFO', 'CORRECTED', 'FLAGGED', 'REJECTED', 'SKIPPED')),
    entity_type     text NOT NULL,
    entity_id       text NOT NULL,
    message         text NOT NULL,
    original_value  jsonb,
    applied_value   jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX import_issues_run_decision ON import_issues (run_id, decision_code);
