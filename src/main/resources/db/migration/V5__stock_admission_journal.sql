ALTER TABLE purchase_requests DROP CONSTRAINT purchase_requests_outcome_check;
ALTER TABLE purchase_requests ADD CONSTRAINT purchase_requests_outcome_check
    CHECK (outcome IN ('IN_PROGRESS', 'SOLD', 'OUT_OF_STOCK', 'NOT_FOUND', 'GAVE_UP', 'ADMISSION_REJECTED'));
ALTER TABLE purchase_requests ADD COLUMN reservation_id UUID;

CREATE TABLE stock_admission_epochs (
    product_id BIGINT PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
    epoch UUID NOT NULL,
    trusted BOOLEAN NOT NULL DEFAULT false
);

-- Independent of the inventory transaction: survives a process dying after Lua.
CREATE TABLE stock_reservations (
    reservation_id UUID PRIMARY KEY,
    product_id BIGINT NOT NULL,
    epoch UUID NOT NULL,
    client_id VARCHAR(64) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 1000),
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'COMMITTED', 'RELEASED', 'NOT_RESERVED', 'STALE_EPOCH', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    resolved_at TIMESTAMPTZ
);
CREATE INDEX stock_reservations_pending_idx ON stock_reservations(product_id) WHERE state='PENDING';
CREATE INDEX stock_reservations_request_idx ON stock_reservations(client_id,key_hash) WHERE state='PENDING';

ALTER TABLE purchase_requests ADD CONSTRAINT purchase_reservation_fk
    FOREIGN KEY (reservation_id) REFERENCES stock_reservations(reservation_id);

-- A non-admitted write makes the advisory epoch untrusted, even for direct SQL.
-- The local setting is an implementation marker, NOT an authorization boundary.
CREATE FUNCTION distrust_stock_admission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('flashsale.redis_admitted', true) IS DISTINCT FROM 'on' THEN
        UPDATE stock_admission_epochs SET trusted=false WHERE product_id=NEW.id AND trusted;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER products_distrust_admission AFTER UPDATE ON products
FOR EACH ROW EXECUTE FUNCTION distrust_stock_admission();
