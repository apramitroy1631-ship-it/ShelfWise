-- Cross-service request tracing (M28): each outbox event remembers the request that caused it, and the relay
-- passes it to the notification service, which logs the same id when it handles the event.
-- Null for events written outside a request (nightly jobs, start-up markers).
--
-- Rollout: adds a nullable column; old code ignores it. Rollback: ALTER TABLE outbox_events DROP COLUMN request_id;

ALTER TABLE outbox_events ADD COLUMN request_id text;
