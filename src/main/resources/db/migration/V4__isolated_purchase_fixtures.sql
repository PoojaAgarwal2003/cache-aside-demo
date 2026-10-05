CREATE TABLE purchase_fixture_modes (
    product_id BIGINT PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('UNSAFE', 'PROTECTED'))
);

INSERT INTO purchase_fixture_modes(product_id,mode)
SELECT DISTINCT p.id, 'PROTECTED'
FROM products p JOIN purchase_requests r ON r.product_id=p.id;
