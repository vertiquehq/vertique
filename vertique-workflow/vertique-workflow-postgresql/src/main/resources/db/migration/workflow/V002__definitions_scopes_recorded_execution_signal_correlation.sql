-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Creates the definition registry, converts the first release's tables to scope keys, and adds the
-- recorded-execution schema. Every instance has a start scope and a definition scope, every task and start-dedup
-- row has a scope, and business keys and start dedup keys are unique per scope. Existing rows read the default
-- scope; stored fingerprints and dedup namespaces stay as they were. The workflow_dedup column scope is renamed
-- dedup_namespace. The recorded-execution schema is the command log and its per-instance queue counters, a trigger
-- that wakes executors when a command becomes runnable, the table of open signal wait records, the index that serves
-- business-key lookup, and the columns the log needs on instances, history, definitions, tasks and timers. Every new
-- column on an existing table is nullable or has a default. A one-time conversion at the end of the script turns the
-- running work of the previous release into commands.
--
-- The ALTER TABLE statements take an ACCESS EXCLUSIVE lock until the migration commits, and so do the index
-- rebuild on workflow_instances and the primary key rebuild on workflow_dedup. The foreign key of the wait table
-- takes a SHARE ROW EXCLUSIVE lock on workflow_instances, which blocks its writers, until the migration commits.
-- Bound the wait for those locks: while a statement waits, new statements on that table queue behind it for up to
-- the timeout, then the migration fails and can be rerun. The timeout does not bound the index builds.
-- Run the script in one transaction (Flyway does), or SET LOCAL has no effect.
-- Except for the dedup column rename, which has no IF EXISTS form, every statement is IF [NOT] EXISTS or drops
-- before it creates, so an operator of a very large installation can add the columns on tasks and then instances and
-- build the two scope keyset indexes ahead of the upgrade, outside this transaction (with CREATE INDEX CONCURRENTLY
-- IF NOT EXISTS), and the migration then builds nothing for them. The business-key index of the first release is
-- always dropped and rebuilt here, and the workflow_dedup rename and primary key change always run here.
-- The business-key lookup index is built inside this transaction, so workflow_instances stays blocked for writers
-- until the build commits, and the blocked time grows with the table. IF NOT EXISTS matches by name, so the
-- statement is a no-op for an index built ahead of the upgrade. An operator of a large installation should build
-- the index ahead of the upgrade, without blocking writers, on an autocommit connection, after adding
-- workflow_instances.scope_key ahead of the upgrade, and check that it is valid, because IF NOT EXISTS keeps an
-- INVALID index:
--   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_instances_business_key_scope_key_definition_id
--       ON workflow_instances (business_key, scope_key, definition_id) WHERE business_key IS NOT NULL;
-- The migration then finds the index and keeps it.
-- Stop every node that runs the previous release before the first node of this release applies the script: its
-- dedup statements name the old column and the old primary key, and it writes timers with a delayed-job execution
-- and tasks without a reservation, and does not know the command log.
SET LOCAL lock_timeout = '30s';

CREATE TABLE IF NOT EXISTS workflow_definitions (
    scope_key VARCHAR(255) NOT NULL,
    definition_id VARCHAR(255) NOT NULL,
    definition_version BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    contract_name VARCHAR(255) NOT NULL,
    state_type_name VARCHAR(255) NOT NULL,
    plan_hash VARCHAR(128) NOT NULL,
    name_binding JSONB NOT NULL DEFAULT '{}',
    status VARCHAR(16) NOT NULL,
    document TEXT,
    document_format VARCHAR(16),
    document_digest VARCHAR(64),
    source_type VARCHAR(64) NOT NULL,
    source_uri TEXT,
    source_revision VARCHAR(255),
    source_author VARCHAR(255),
    source_upload_id VARCHAR(255),
    loaded_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    publish_token UUID,
    failure_reason VARCHAR(64),
    CONSTRAINT pk_workflow_definitions PRIMARY KEY (scope_key, definition_id, definition_version),
    CONSTRAINT ck_workflow_definitions_version CHECK (definition_version > 0),
    CONSTRAINT ck_workflow_definitions_keys CHECK (scope_key !~ '[[:cntrl:]]' AND definition_id !~ '[[:cntrl:]]'),
    CONSTRAINT ck_workflow_definitions_binding CHECK (jsonb_typeof(name_binding) = 'object'),
    CONSTRAINT ck_workflow_definitions_kind CHECK (kind IN ('CODE','DOCUMENT')),
    CONSTRAINT ck_workflow_definitions_status CHECK (status IN ('CANDIDATE','PUBLISHING','AVAILABLE','DEPRECATED','RETIRED')),
    CONSTRAINT ck_workflow_definitions_claim CHECK ((status = 'PUBLISHING') = (publish_token IS NOT NULL)),
    CONSTRAINT ck_workflow_definitions_document CHECK (
        (kind = 'DOCUMENT' AND document IS NOT NULL AND document_format IS NOT NULL)
        OR (kind = 'CODE' AND document IS NULL AND document_format IS NULL AND scope_key = 'default')),
    CONSTRAINT ck_workflow_definitions_digest
        CHECK ((kind = 'DOCUMENT') = (document_digest IS NOT NULL) AND (document_digest IS NULL OR document_digest ~ '^[0-9a-f]{64}$'))
);
CREATE INDEX IF NOT EXISTS idx_workflow_definitions_id ON workflow_definitions (definition_id);

-- The current version of a definition id in a scope. A scope without a row follows the default scope. A scope names a
-- version of its own or of the default scope, and the default scope names only itself. The foreign key keeps a
-- definition row that a record names from being deleted.
CREATE TABLE IF NOT EXISTS workflow_definition_activations (
    scope_key VARCHAR(255) NOT NULL,
    definition_id VARCHAR(255) NOT NULL,
    target_scope_key VARCHAR(255) NOT NULL,
    definition_version BIGINT NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_workflow_definition_activations PRIMARY KEY (scope_key, definition_id),
    CONSTRAINT ck_workflow_definition_activations_version CHECK (definition_version > 0),
    CONSTRAINT ck_workflow_definition_activations_keys CHECK (
        scope_key !~ '[[:cntrl:]]' AND definition_id !~ '[[:cntrl:]]' AND target_scope_key !~ '[[:cntrl:]]'),
    CONSTRAINT ck_workflow_definition_activations_target CHECK (
        (target_scope_key = scope_key OR target_scope_key = 'default')
        AND (scope_key <> 'default' OR target_scope_key = 'default')),
    CONSTRAINT fk_workflow_definition_activations_target
        FOREIGN KEY (target_scope_key, definition_id, definition_version)
        REFERENCES workflow_definitions (scope_key, definition_id, definition_version) ON DELETE RESTRICT
);

-- Tasks first, matching the engine's lock order (tasks before instances).
ALTER TABLE workflow_tasks ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default';
ALTER TABLE workflow_instances
    ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default',
    ADD COLUMN IF NOT EXISTS definition_scope_key VARCHAR(255) NOT NULL DEFAULT 'default';

-- A business key is unique per start scope and definition id.
DROP INDEX IF EXISTS idx_workflow_instances_business_key;
CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_instances_business_key
    ON workflow_instances (scope_key, definition_id, business_key) WHERE business_key IS NOT NULL;

-- Keyset indexes for the scope-filtered queries: the scope, then the table's keyset order.
CREATE INDEX IF NOT EXISTS idx_workflow_tasks_scope_updated_at
    ON workflow_tasks (scope_key, updated_at DESC, task_id ASC);
CREATE INDEX IF NOT EXISTS idx_workflow_instances_scope_updated_at
    ON workflow_instances (scope_key, updated_at DESC, id ASC);

-- The dedup column scope is a namespace (the definition id for start rows); scope_key is the start scope.
ALTER TABLE workflow_dedup RENAME COLUMN scope TO dedup_namespace;
ALTER TABLE workflow_dedup ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default';
ALTER TABLE workflow_dedup
    DROP CONSTRAINT IF EXISTS workflow_dedup_pkey,
    ADD CONSTRAINT workflow_dedup_pkey PRIMARY KEY (kind, scope_key, dedup_namespace, key);

-- ---------------------------------------------------------------------------------------------------------------
-- Recorded execution: the command log, the signal wait records, and the columns they need.
-- ---------------------------------------------------------------------------------------------------------------

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
    -- where a signal command came from; null for commands that record none
    provenance_json       JSONB        NULL,
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

-- One open row per wait entry of a computed signal correlation key. The engine generates wait_id (a version 7 UUID),
-- so the column has no default. fork_step_id and branch_id hold '' outside a branch, so the unique key binds the
-- main path too.
CREATE TABLE IF NOT EXISTS workflow_signal_waits (
    wait_id         UUID         NOT NULL PRIMARY KEY,
    instance_id     UUID         NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    step_id         VARCHAR(255) NOT NULL,
    fork_step_id    VARCHAR(255) NOT NULL,
    branch_id       VARCHAR(255) NOT NULL,
    scope_key       VARCHAR(255) NOT NULL,
    contract_name   VARCHAR(255) NOT NULL,
    definition_id   VARCHAR(255) NOT NULL,
    signal_name     VARCHAR(255) NOT NULL,
    correlation_key VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_workflow_signal_waits_instance_step_branch UNIQUE (instance_id, step_id, fork_step_id, branch_id)
);

-- Delivery of a signal by contract, signal name and key.
CREATE INDEX IF NOT EXISTS idx_workflow_signal_waits_contract_signal_key
    ON workflow_signal_waits (contract_name, signal_name, correlation_key);

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

-- Business-key lookup without a definition or a scope. Not unique: a business key is unique only per scope and
-- definition id, which idx_workflow_instances_business_key already enforces.
CREATE INDEX IF NOT EXISTS idx_workflow_instances_business_key_scope_key_definition_id
    ON workflow_instances (business_key, scope_key, definition_id) WHERE business_key IS NOT NULL;

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

-- ---------------------------------------------------------------------------------------------------------------
-- One-time conversion of the running work of the previous release. Every node of the previous release is stopped
-- before this script runs, so nothing else writes these rows while it converts them.
-- ---------------------------------------------------------------------------------------------------------------

-- Every existing instance logs its full-state baseline when it is next executed.
UPDATE workflow_instances SET baseline_logged = FALSE;

-- Each scheduled timer becomes one timer command at the timer's fire time, linked from the timer. The timers of an
-- instance get ascending sequences by fire time, then timer id. A converted timer keeps its context except the
-- identity snapshot, which no command may carry to an executor.
WITH due AS (
    SELECT t.timer_id,
           t.workflow_id,
           t.fire_at,
           t.task_id,
           CASE t.purpose
               WHEN 'STANDALONE' THEN 'FIRE'
               WHEN 'SIGNAL_TIMEOUT' THEN 'TIMEOUT'
               WHEN 'TASK_DUE' THEN 'DUE'
               ELSE 'REMINDER'
           END AS timer_kind,
           row_number() OVER (PARTITION BY t.workflow_id ORDER BY t.fire_at, t.timer_id) AS seq
    FROM workflow_timers t
    WHERE t.status = 'SCHEDULED' AND t.command_id IS NULL
), converted AS (
    INSERT INTO workflow_commands (workflow_id, seq, kind, timer_kind, timer_id, task_id, state, recorded_at,
                                   available_at, fire_at)
    SELECT workflow_id, seq, 'TIMER', timer_kind, timer_id, task_id, 'PENDING', now(), fire_at, fire_at
    FROM due
    RETURNING id, timer_id
)
UPDATE workflow_timers t
SET command_id = converted.id,
    metadata = t.metadata #- '{context,identity-snapshot}'
FROM converted
WHERE t.timer_id = converted.timer_id;

-- Each branch whose retry was scheduled becomes one branch-addressed retry command, available at the retry time (at
-- once when it has none), and the branch runs again. Its sequences follow the instance's timer commands.
WITH waiting AS (
    SELECT b.id,
           b.workflow_id,
           b.fork_step_id,
           b.branch_id,
           COALESCE(b.next_retry_at, now()) AS available_at,
           (SELECT count(*) FROM workflow_commands c WHERE c.workflow_id = b.workflow_id)
               + row_number() OVER (PARTITION BY b.workflow_id
                                    ORDER BY COALESCE(b.next_retry_at, now()), b.fork_step_id, b.branch_id) AS seq
    FROM workflow_branch_tokens b
    WHERE b.status = 'RETRY_SCHEDULED'
), converted AS (
    INSERT INTO workflow_commands (workflow_id, seq, kind, target_json, state, recorded_at, available_at)
    SELECT workflow_id, seq, 'RETRY',
           jsonb_build_object('forkStepId', fork_step_id, 'branchId', branch_id),
           'PENDING', now(), available_at
    FROM waiting
    RETURNING workflow_id
)
UPDATE workflow_branch_tokens b
SET status = 'RUNNING', next_retry_at = NULL, version = b.version + 1, updated_at = now()
WHERE b.status = 'RETRY_SCHEDULED';

-- Each instance that received a command gets its queue row, counting those commands.
INSERT INTO workflow_command_queues (workflow_id, next_seq, open_signals)
SELECT workflow_id, max(seq) + 1, 0
FROM workflow_commands
GROUP BY workflow_id
ON CONFLICT (workflow_id) DO NOTHING;

-- The delayed jobs of the previous release that fired timers are not run by this release. Remove the live ones from
-- the delayed-job table named job_executions in the schema the migration runs in, when that is a plain table; finished
-- job history stays. A deployment whose job tables are elsewhere runs the equivalent statement by hand before the
-- first node of this release starts.
DO $$
DECLARE
    jobs regclass := to_regclass(quote_ident(current_schema()) || '.job_executions');
BEGIN
    IF jobs IS NOT NULL AND (SELECT c.relkind FROM pg_class c WHERE c.oid = jobs) = 'r' THEN
        EXECUTE format('DELETE FROM %s WHERE handler = ''jobs/delayed/vertique.workflow.timer.fire/execute'' '
                       || 'AND state IN (''ENQUEUED'', ''PROCESSING'', ''FAILED'', ''ABANDONED'')', jobs);
    END IF;
END
$$;
