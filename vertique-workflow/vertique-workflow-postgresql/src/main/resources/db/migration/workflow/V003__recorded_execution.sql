-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Adds the recorded-execution schema: the command log and its per-instance queue counters, a trigger that wakes
-- executors when a command becomes runnable, and the columns the log needs on instances, history, definitions,
-- tasks and timers. Existing rows keep their values; every new column on an existing table is nullable or has a
-- default.
--
-- The ALTER TABLE statements take an ACCESS EXCLUSIVE lock until the migration commits. Bound the wait for those
-- locks: while a statement waits, new statements on that table queue behind it for up to the timeout, then the
-- migration fails and can be rerun.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- Every statement is IF [NOT] EXISTS or drops before it creates, so the script can be rerun after a failure.
-- Stop every node that runs the previous release before the first node of this release applies the script: the
-- previous release writes timers with a delayed-job execution and tasks without a reservation, and does not
-- know the command log.
SET LOCAL lock_timeout = '30s';

CREATE TABLE IF NOT EXISTS workflow_commands (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id           UUID         NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    seq                   BIGINT       NOT NULL,
    kind                  VARCHAR(20)  NOT NULL,
    timer_kind            VARCHAR(20)  NULL,
    timer_id              UUID         NULL,
    task_id               UUID         NULL,
    target_json           JSONB        NULL,
    payload_json          JSONB        NULL,
    state                 VARCHAR(20)  NOT NULL,
    recorded_at           TIMESTAMPTZ  NOT NULL,
    available_at          TIMESTAMPTZ  NOT NULL,
    fire_at               TIMESTAMPTZ  NULL,
    attempts              INT          NOT NULL DEFAULT 0,
    transient_count       INT          NOT NULL DEFAULT 0,
    deferral_count        INT          NOT NULL DEFAULT 0,
    claim_token           UUID         NULL,
    lease_until           TIMESTAMPTZ  NULL,
    claimed_by            VARCHAR(255) NULL,
    context_json          JSONB        NULL,
    explicit_carrier_json JSONB        NULL,
    actor_kind            VARCHAR(20)  NULL,
    actor_id              VARCHAR(255) NULL,
    actor_reason          VARCHAR(255) NULL,
    on_behalf_of          VARCHAR(255) NULL,
    attribution_status    VARCHAR(20)  NULL,
    dedup_key             VARCHAR(255) NULL,
    settled_at            TIMESTAMPTZ  NULL,
    last_error            TEXT         NULL,
    CONSTRAINT ck_workflow_commands_state CHECK (state IN (
        'PENDING','DEFERRED','CLAIMED','PARKED','DEAD','COMPLETE','REJECTED','STALE','FAILED','SUPERSEDED',
        'CANCELLED')),
    CONSTRAINT ck_workflow_commands_kind CHECK (kind IN (
        'START','SIGNAL','COMPLETION','RETRY','MIGRATION','TIMER')),
    CONSTRAINT ck_workflow_commands_timer_kind CHECK (
        (kind = 'TIMER' AND timer_kind IS NOT NULL
         AND timer_kind IN ('FIRE','TIMEOUT','DUE','REMINDER'))
        OR (kind <> 'TIMER' AND timer_kind IS NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_workflow_commands_workflow_seq
    ON workflow_commands (workflow_id, seq);

-- blocking candidates per instance
CREATE INDEX IF NOT EXISTS idx_workflow_commands_active_by_workflow
    ON workflow_commands (workflow_id, seq)
    WHERE state IN ('PENDING','DEFERRED','CLAIMED');

-- peek and wake-up
CREATE INDEX IF NOT EXISTS idx_workflow_commands_active_available_at
    ON workflow_commands (available_at)
    WHERE state IN ('PENDING','DEFERRED','CLAIMED');

CREATE INDEX IF NOT EXISTS idx_workflow_commands_parked_dead
    ON workflow_commands (workflow_id)
    WHERE state IN ('PARKED','DEAD');

CREATE TABLE IF NOT EXISTS workflow_command_queues (
    workflow_id  UUID   PRIMARY KEY REFERENCES workflow_instances(id) ON DELETE CASCADE,
    next_seq     BIGINT NOT NULL,
    open_signals INT    NOT NULL DEFAULT 0
);

-- Wakes executors when a command becomes runnable. The payload is the command's availability in epoch
-- milliseconds; it names no instance. PostgreSQL delivers the notification when the transaction commits.
CREATE OR REPLACE FUNCTION workflow_commands_notify() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('vertique_workflow_commands',
                      (extract(epoch from NEW.available_at) * 1000)::bigint::text);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_workflow_commands_notify ON workflow_commands;
CREATE TRIGGER trg_workflow_commands_notify
    AFTER INSERT OR UPDATE OF state, available_at ON workflow_commands
    FOR EACH ROW
    WHEN (NEW.state IN ('PENDING','DEFERRED'))
    EXECUTE FUNCTION workflow_commands_notify();

-- Instances: a pending instance has no state or step yet.
ALTER TABLE workflow_instances ALTER COLUMN state_json DROP NOT NULL;
ALTER TABLE workflow_instances ALTER COLUMN current_step_id DROP NOT NULL;
ALTER TABLE workflow_instances ADD COLUMN IF NOT EXISTS baseline_logged BOOLEAN NOT NULL DEFAULT TRUE;

-- History: attribution, execution and definition identity, and state versions; older rows keep nulls.
ALTER TABLE workflow_history
    ADD COLUMN IF NOT EXISTS actor_kind            VARCHAR(20)  NULL,
    ADD COLUMN IF NOT EXISTS actor_id              VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS actor_reason          VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS on_behalf_of          VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS attribution_status    VARCHAR(20)  NULL,
    ADD COLUMN IF NOT EXISTS executor_id           VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS command_id            BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS step_id               VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS outcome               VARCHAR(40)  NULL,
    ADD COLUMN IF NOT EXISTS definition_scope_key  VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS definition_id         VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS definition_version    BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS plan_hash             VARCHAR(64)  NULL,
    ADD COLUMN IF NOT EXISTS state_version_before  BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS state_version_after   BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS patch_json            JSONB        NULL,
    ADD COLUMN IF NOT EXISTS entry_version         SMALLINT     NOT NULL DEFAULT 1;

-- Definitions: the signal names and start schema of the plan, null until a plan is next built for the row.
ALTER TABLE workflow_definitions
    ADD COLUMN IF NOT EXISTS signal_names_json JSONB NULL,
    ADD COLUMN IF NOT EXISTS start_schema_json JSONB NULL;

-- Tasks: a task being completed by a command has status COMPLETING and names that command.
ALTER TABLE workflow_tasks DROP CONSTRAINT IF EXISTS workflow_tasks_status_check;
ALTER TABLE workflow_tasks
    ADD CONSTRAINT workflow_tasks_status_check
    CHECK (status IN ('OPEN','COMPLETING','COMPLETED','CANCELLED','EXPIRED'));
ALTER TABLE workflow_tasks
    ADD COLUMN IF NOT EXISTS reservation_command_id    BIGINT  NULL,
    ADD COLUMN IF NOT EXISTS require_version_stability BOOLEAN NULL;

-- Timers: fired through commands, no longer through delayed jobs.
ALTER TABLE workflow_timers ALTER COLUMN delayed_job_execution_id DROP NOT NULL;
ALTER TABLE workflow_timers ADD COLUMN IF NOT EXISTS command_id BIGINT NULL;
