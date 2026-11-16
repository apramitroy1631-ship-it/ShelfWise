-- Search (bug fix): typo-tolerant, any-order word search for products, stock and orders (see core/search/TextSearch).
-- pg_trgm gives "word_similarity" (typo matching) and lets ILIKE '%text%' use an index.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX products_name_trgm ON products USING gin (name gin_trgm_ops);
CREATE INDEX products_sku_trgm ON products USING gin (sku gin_trgm_ops);
CREATE INDEX customer_orders_name_trgm ON customer_orders USING gin (customer_name gin_trgm_ops);
