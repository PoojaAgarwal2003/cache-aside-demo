CREATE TABLE purchase_requests (
    client_id VARCHAR(64) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 1000),
    strategy VARCHAR(32) NOT NULL,
    outcome VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    stock_left INTEGER,
    product_version BIGINT,
    attempts INTEGER NOT NULL DEFAULT 1 CHECK (attempts BETWEEN 1 AND 20),
    original_request_id UUID NOT NULL,
    purchase_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (client_id, key_hash),
    CHECK (outcome IN ('IN_PROGRESS', 'SOLD', 'OUT_OF_STOCK', 'NOT_FOUND')),
    CHECK ((outcome = 'SOLD') = (purchase_id IS NOT NULL)),
    CHECK (outcome <> 'SOLD' OR (stock_left IS NOT NULL AND product_version IS NOT NULL)),
    CHECK ((outcome = 'IN_PROGRESS') = (completed_at IS NULL))
);

CREATE INDEX purchase_requests_product_idx ON purchase_requests(product_id);

-- A committed claim must already contain its terminal business result.
-- The deferred trigger queries current state, not the INSERT's old NEW tuple.
CREATE FUNCTION require_terminal_purchase() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM purchase_requests
        WHERE client_id = NEW.client_id AND key_hash = NEW.key_hash AND outcome = 'IN_PROGRESS'
    ) THEN
        RAISE EXCEPTION 'A purchase claim cannot commit without a terminal result';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER purchase_request_terminal
AFTER INSERT OR UPDATE ON purchase_requests
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_terminal_purchase();

-- One persisted row is both the request result and, for SOLD, its ledger entry.
-- No product FK: deleting a product must not erase its purchase history.
CREATE VIEW purchase_ledger AS
SELECT purchase_id, client_id, key_hash, product_id, quantity, strategy,
       stock_left, product_version, original_request_id, completed_at
FROM purchase_requests
WHERE outcome = 'SOLD';
