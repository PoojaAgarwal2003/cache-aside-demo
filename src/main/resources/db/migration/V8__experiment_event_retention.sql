ALTER TABLE demo_runs ADD COLUMN events_created BIGINT NOT NULL DEFAULT 0;
UPDATE demo_runs r SET events_created =
    (SELECT count(*) FROM demo_run_events e WHERE e.run_id=r.run_id);
