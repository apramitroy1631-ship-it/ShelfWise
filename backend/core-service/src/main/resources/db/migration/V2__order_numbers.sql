-- Order numbers for orders created in the app.
-- Imported orders use ORD-000001 .. ORD-014960; app orders start at ORD-100000 so the two never meet.
-- The primary key on customer_orders.id still guarantees no duplicate ever gets in.
CREATE SEQUENCE order_number_seq START WITH 100000;
