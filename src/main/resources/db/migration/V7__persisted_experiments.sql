CREATE TABLE demo_runs (
    run_id UUID PRIMARY KEY,
    state VARCHAR(24) NOT NULL CHECK (state IN
        ('STARTING','RUNNING','DRAINING','COMPLETED','CANCELLED','INCONCLUSIVE','INTERRUPTED','FAILED')),
    parameters JSONB NOT NULL,
    environment JSONB NOT NULL,
    result JSONB,
    error VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    ended_at TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT true,
    CHECK (active OR (ended_at IS NOT NULL AND result IS NOT NULL))
);
CREATE UNIQUE INDEX demo_one_active_run ON demo_runs ((true)) WHERE active;

CREATE TABLE demo_run_fixtures (
    run_id UUID NOT NULL REFERENCES demo_runs,
    case_index INTEGER NOT NULL CHECK (case_index BETWEEN 0 AND 4),
    product_id BIGINT NOT NULL UNIQUE,
    label VARCHAR(40) NOT NULL,
    strategy VARCHAR(32) NOT NULL,
    initial_stock INTEGER NOT NULL,
    initial_version BIGINT NOT NULL,
    PRIMARY KEY (run_id, case_index)
);

CREATE TABLE demo_run_attempts (
    attempt_id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES demo_runs,
    case_index INTEGER NOT NULL,
    buyer INTEGER NOT NULL CHECK (buyer BETWEEN 0 AND 100),
    phase VARCHAR(24) NOT NULL,
    client_id VARCHAR(64) NOT NULL,
    key_hash CHAR(64),
    method VARCHAR(8) NOT NULL,
    path VARCHAR(256) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'DISPATCHING',
    response JSONB,
    http_status INTEGER,
    elapsed_ms DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (run_id,case_index) REFERENCES demo_run_fixtures
);
CREATE INDEX demo_attempts_run ON demo_run_attempts(run_id,attempt_id);

CREATE TABLE demo_run_events (
    sequence BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES demo_runs,
    kind VARCHAR(32) NOT NULL,
    detail JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX demo_events_cursor ON demo_run_events(run_id,sequence);
