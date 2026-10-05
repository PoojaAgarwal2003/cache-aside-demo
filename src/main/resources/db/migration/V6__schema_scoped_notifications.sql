CREATE OR REPLACE FUNCTION notify_product_change() RETURNS trigger LANGUAGE plpgsql AS $$
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
        'schema', TG_TABLE_SCHEMA, 'operation', TG_OP,
        'productId', changed_id, 'version', changed_version
    )::text);
    RETURN NULL;
END $$;
