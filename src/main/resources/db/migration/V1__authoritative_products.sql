DO $$
BEGIN
    IF current_database() NOT IN ('flashsale_lab', 'flashsale_test') THEN
        RAISE EXCEPTION 'FlashSale migrations require a dedicated flashsale_lab or flashsale_test database';
    END IF;
END $$;

CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    -- Intentionally no stock >= 0 constraint: the later demo-only NONE race
    -- must be able to demonstrate negative inventory. Not a production schema.
    stock INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION advance_product_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.version := OLD.version + 1;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;

CREATE TRIGGER products_before_update
BEFORE UPDATE ON products
FOR EACH ROW EXECUTE FUNCTION advance_product_version();

CREATE FUNCTION notify_product_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    changed_id BIGINT;
    changed_version BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        changed_id := OLD.id;
        changed_version := OLD.version;
    ELSE
        changed_id := NEW.id;
        changed_version := NEW.version;
    END IF;
    PERFORM pg_notify('product_changes', json_build_object(
        'operation', TG_OP, 'productId', changed_id, 'version', changed_version
    )::text);
    RETURN NULL;
END $$;

CREATE TRIGGER products_after_change
AFTER INSERT OR UPDATE OR DELETE ON products
FOR EACH ROW EXECUTE FUNCTION notify_product_change();
