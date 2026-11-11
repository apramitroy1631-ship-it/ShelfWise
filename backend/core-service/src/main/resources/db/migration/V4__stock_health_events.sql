-- Low-stock notifications (D7) need to know when an item moves between "in stock", "running low" and
-- "out of stock". Every stock change in the app is an UPDATE of an inventory row (orders, cancellations,
-- adjustments, recounts, PO receipts), so the database itself writes a marker to the outbox, in the same
-- transaction, whenever an item crosses one of those lines. No code path can forget to send it.
-- The relay turns the marker into the store's current counts at publish time (docs/events.md).
--
-- Rollout: additive (a function and a trigger). Rollback: DROP TRIGGER inventory_stock_health ON inventory;
-- DROP FUNCTION stock_bucket(integer, integer); DROP FUNCTION inventory_stock_health_marker();

CREATE FUNCTION stock_bucket(quantity integer, reorder_level integer) RETURNS text
    LANGUAGE sql IMMUTABLE AS
$$
    SELECT CASE WHEN quantity = 0 THEN 'OUT' WHEN quantity <= reorder_level THEN 'LOW' ELSE 'OK' END
$$;

CREATE FUNCTION inventory_stock_health_marker() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF stock_bucket(OLD.quantity, OLD.reorder_level) <> stock_bucket(NEW.quantity, NEW.reorder_level) THEN
        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)
        VALUES (gen_random_uuid(), 'STORE', NEW.store_id, 'StockHealthChanged',
                jsonb_build_object('storeId', NEW.store_id, 'sku', NEW.sku,
                                   'from', stock_bucket(OLD.quantity, OLD.reorder_level),
                                   'to', stock_bucket(NEW.quantity, NEW.reorder_level)));
    END IF;
    RETURN NEW;
END
$$;

-- UPDATE only: the import inserts rows (D28) and its starting state is sent as one snapshot per store instead.
CREATE TRIGGER inventory_stock_health
    AFTER UPDATE OF quantity, reorder_level ON inventory
    FOR EACH ROW EXECUTE FUNCTION inventory_stock_health_marker();
