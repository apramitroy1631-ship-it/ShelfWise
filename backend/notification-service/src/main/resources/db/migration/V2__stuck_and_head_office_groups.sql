-- More grouped notifications (D7): stuck orders (D32) and stuck POs (D22) for store managers, and orders waiting
-- for review (D10) for head office. Head office's stock health reuses OUT_OF_STOCK / RUNNING_LOW with audience
-- 'ROLE:ADMIN' (counted across all stores).
--
-- Rollout: widens a CHECK only; existing rows stay valid. Rollback: delete rows of the three new kinds, then put
-- the V1 list back.

ALTER TABLE notifications.notifications DROP CONSTRAINT notifications_kind_check;
ALTER TABLE notifications.notifications ADD CONSTRAINT notifications_kind_check CHECK (kind IN (
    'OUT_OF_STOCK', 'RUNNING_LOW', 'NEEDS_APPROVAL', 'NEEDS_SECOND_APPROVAL', 'PO_DECISION',
    'STUCK_ORDERS', 'STUCK_POS', 'NEEDS_REVIEW'));
