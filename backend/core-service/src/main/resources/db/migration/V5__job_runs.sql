-- Scheduled jobs (M23) record every run, so "did the nightly job run, and what did it do?" has an answer
-- (monitoring evidence) and an admin can see it without reading logs.
--
-- Rollout: additive (one table). Rollback: DROP TABLE job_runs.

CREATE TABLE job_runs (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job          text NOT NULL CHECK (job IN ('EXPIRE_DRAFTS', 'LOW_STOCK_RECOUNT')),
    trigger      text NOT NULL CHECK (trigger IN ('SCHEDULE', 'MANUAL')),
    started_by   text REFERENCES users (id),          -- null = the schedule
    started_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at  timestamptz,
    outcome      text NOT NULL CHECK (outcome IN ('DONE', 'SKIPPED', 'FAILED')),
    affected     integer NOT NULL DEFAULT 0,          -- drafts expired / stores refreshed
    details      jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX job_runs_recent ON job_runs (job, started_at DESC);
