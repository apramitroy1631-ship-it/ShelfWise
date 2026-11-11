-- Notification service tables, in its own schema (DL-12). Grouped the D7 way, Instagram style:
-- one record per audience + group ("out of stock in S01"), holding the CURRENT count, plus one record per
-- PO decision. Each person's "seen up to" time lives in reads, so two managers of one store share the record
-- but each has their own unread state.
--
-- Rollout: new schema only. Rollback: DROP SCHEMA notifications CASCADE (notifications are rebuilt from the
-- next snapshots the core service sends).

CREATE TABLE notifications.notifications (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    audience          text NOT NULL,                    -- 'STORE:S01' (that store's managers) or 'ROLE:FINANCE_APPROVER'
    kind              text NOT NULL CHECK (kind IN (
                          'OUT_OF_STOCK', 'RUNNING_LOW', 'NEEDS_APPROVAL', 'NEEDS_SECOND_APPROVAL', 'PO_DECISION')),
    group_key         text NOT NULL,                    -- the kind for groups; 'PO_DECISION:PO-10003' for one decision
    item_count        integer CHECK (item_count >= 0),  -- current count for groups; null for single notifications
    latest            text,                             -- product name or PO number shown in the text
    oldest_since      timestamptz,                      -- finance: oldest waiting PO, so grouping never hides age (D7)
    message           text,                             -- single notifications only (PO decisions)
    ref               text,                             -- what it opens, e.g. the PO number
    as_of             timestamptz NOT NULL,             -- time of the newest state applied; older messages are ignored
    last_increase_at  timestamptz NOT NULL,             -- when this service last made it "new" (count went UP); unread if seen before this
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notifications_one_per_group UNIQUE (audience, group_key)
);
CREATE INDEX notifications_feed ON notifications.notifications (audience, last_increase_at DESC);

CREATE TABLE notifications.reads (
    user_id          text NOT NULL,
    notification_id  bigint NOT NULL REFERENCES notifications.notifications (id) ON DELETE CASCADE,
    seen_at          timestamptz NOT NULL,
    PRIMARY KEY (user_id, notification_id)
);

-- Delivery is at-least-once (DL-03): an event id seen before is skipped.
CREATE TABLE notifications.processed_events (
    event_id      uuid PRIMARY KEY,
    event_type    text NOT NULL,
    processed_at  timestamptz NOT NULL DEFAULT now()
);
