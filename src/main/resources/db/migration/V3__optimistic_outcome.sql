ALTER TABLE purchase_requests DROP CONSTRAINT purchase_requests_outcome_check;
ALTER TABLE purchase_requests ADD CONSTRAINT purchase_requests_outcome_check
    CHECK (outcome IN ('IN_PROGRESS', 'SOLD', 'OUT_OF_STOCK', 'NOT_FOUND', 'GAVE_UP'));
