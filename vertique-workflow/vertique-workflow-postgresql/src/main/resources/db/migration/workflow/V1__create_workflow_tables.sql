-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Workflow instances: snapshot + status + concurrency token
CREATE TABLE workflow_instances (
    id                    UUID PRIMARY KEY,
    definition_id         VARCHAR(255) NOT NULL,
    definition_version    BIGINT NOT NULL,
    plan_hash             VARCHAR(64) NOT NULL,
    version               BIGINT NOT NULL DEFAULT 0,
    status                VARCHAR(20) NOT NULL,
    business_key          VARCHAR(255),
    subject_type          VARCHAR(64),
    subject_id            VARCHAR(255),
    subject_version       VARCHAR(64),
    current_step_id       VARCHAR(255) NOT NULL,
    wait_type             VARCHAR(32),
    wait_key              VARCHAR(255),
    wait_aux_id           UUID,
    state_json            JSONB NOT NULL,
    error_type            VARCHAR(255),
    error_message         TEXT,
    -- durable context captured at start time (ADR-0065 {"context": ...} carrier shape);
    -- NULL means no ambient durable context was present at start (empty capture)
    metadata              JSONB NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at          TIMESTAMPTZ,
    -- cycle 4: soft-delete timestamp; NULL means not yet archived
    archived_at           TIMESTAMPTZ NULL
);

CREATE UNIQUE INDEX idx_workflow_instances_business_key
    ON workflow_instances (definition_id, business_key)
    WHERE business_key IS NOT NULL;

CREATE INDEX idx_workflow_instances_wait
    ON workflow_instances (status, wait_type, wait_key);

CREATE INDEX idx_workflow_instances_subject
    ON workflow_instances (subject_type, subject_id);

-- Composite index supports the keyset pagination used by PgWorkflowInstanceRepository.findFiltered:
-- ORDER BY updated_at DESC, id ASC. The leading column matches keyset comparison and the trailing
-- column is the unique tiebreaker.
CREATE INDEX idx_workflow_instances_updated_at
    ON workflow_instances (updated_at DESC, id ASC);

CREATE INDEX idx_workflow_instances_wait_aux_id
    ON workflow_instances (wait_aux_id)
    WHERE wait_aux_id IS NOT NULL;

-- cycle 4: drives the archive-sweep background job (status + completed_at cutoff, unarchived only)
CREATE INDEX idx_workflow_instances_archive_sweep
    ON workflow_instances (status, completed_at)
    WHERE archived_at IS NULL
      AND status IN ('COMPLETED','FAILED','CANCELLED','EXPIRED','COMPENSATED');

-- cycle 4: drives the purge-archived background job (cutoff on archived_at, archived rows only)
CREATE INDEX idx_workflow_instances_archived_at
    ON workflow_instances (archived_at)
    WHERE archived_at IS NOT NULL;

-- Append-only history: monotonic per-instance sequence
CREATE TABLE workflow_history (
    workflow_id           UUID NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    sequence              BIGINT NOT NULL,
    entry_type            VARCHAR(64) NOT NULL,
    payload_json          JSONB NOT NULL,
    recorded_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (workflow_id, sequence)
);

-- Dedup: scoped (kind, scope, key) — semantics:
--   kind='start',        scope=definition_id,              key=idempotency_key
--   kind='signal',       scope=workflow_instance_id::text, key=signal_dedup_key
--   kind='task-complete',scope=task_id::text,              key=idempotency_key
--   kind='task-reassign',scope=task_id::text,              key=idempotency_key
-- fingerprint is NULL for cycle-1 'start' and cycle-2 'signal' rows; populated
-- for cycle-3 'task-complete' and 'task-reassign' rows (SHA-256 hex, 64 chars).
CREATE TABLE workflow_dedup (
    kind                  VARCHAR(16) NOT NULL,
    scope                 VARCHAR(255) NOT NULL,
    key                   VARCHAR(255) NOT NULL,
    workflow_id           UUID NOT NULL,
    fingerprint           VARCHAR(64) NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (kind, scope, key)
);

CREATE INDEX idx_workflow_dedup_workflow_id ON workflow_dedup (workflow_id);

-- cycle 4: CASCADE FK so dedup rows are cleaned up when the workflow instance is purged.
-- DEFERRABLE INITIALLY DEFERRED is required because the start path INSERTs the dedup row before the
-- workflow_instances row (race-claim ordering); a non-deferred FK would fail at the dedup INSERT
-- because its workflow_id has not been inserted yet. The constraint is checked at COMMIT time, by
-- which point the instance row exists.
ALTER TABLE workflow_dedup
    ADD CONSTRAINT fk_workflow_dedup_workflow_id
        FOREIGN KEY (workflow_id) REFERENCES workflow_instances(id) ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED;

-- Workflow timers: source-of-truth for timer firing state.
-- timer_id is the primary key (not (workflow_id, step_id)) so loops or retries
-- that revisit the same step produce distinct timer rows. workflow_timers.status
-- is the cancellation source-of-truth: signal-before-timeout race, in-tx
-- WorkflowOperations.cancel(...), and the firing job's idempotency all gate on
-- the SCHEDULED → {FIRED|CANCELLED|FAILED} transition serialized via FOR UPDATE.
-- delayed_job_execution_id is NOT NULL because the recorder enqueues the
-- delayed_jobs row first, then inserts the workflow_timers row in the same tx —
-- there is no observable NULL window. Recovery re-enqueue replaces the value
-- in a single tx via TimerStore.updateExecutionId(...).
-- metadata carries durable propagation context (FR-CTX-178) captured at timer-create
-- time; it is the recovery-state copy of job_executions.metadata for the same timer.
CREATE TABLE workflow_timers (
    timer_id                    UUID         PRIMARY KEY,
    workflow_id                 UUID         NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    step_id                     TEXT         NOT NULL,
    status                      TEXT         NOT NULL CHECK (status IN ('SCHEDULED','FIRED','CANCELLED','FAILED')),
    fire_at                     TIMESTAMPTZ  NOT NULL,
    delayed_job_execution_id    UUID         NOT NULL,
    scheduled_at                TIMESTAMPTZ  NOT NULL,
    fired_at                    TIMESTAMPTZ  NULL,
    cancelled_at                TIMESTAMPTZ  NULL,
    failed_at                   TIMESTAMPTZ  NULL,
    failure_reason              TEXT         NULL,
    -- cycle 4: timer purpose drives executor dispatch and cancel-cascade routing
    purpose                     TEXT         NOT NULL DEFAULT 'STANDALONE'
                                    CHECK (purpose IN ('STANDALONE','SIGNAL_TIMEOUT','TASK_DUE','TASK_REMINDER')),
    -- cycle 4: task identity for TASK_DUE and TASK_REMINDER timers (no FK — avoids insert-order deadlock)
    task_id                     UUID         NULL,
    -- durable propagation context (FR-CTX-178); NULL ≡ empty map
    metadata                    JSONB        NULL
);

-- cycle 4: enforce purpose/task_id pairing invariant declared on TimerRecord
ALTER TABLE workflow_timers
    ADD CONSTRAINT ck_workflow_timers_task_id_purpose
    CHECK (
        (purpose IN ('TASK_DUE','TASK_REMINDER') AND task_id IS NOT NULL)
        OR (purpose IN ('STANDALONE','SIGNAL_TIMEOUT') AND task_id IS NULL)
    );

CREATE INDEX idx_workflow_timers_orphan_scan
    ON workflow_timers (status, fire_at)
    WHERE status = 'SCHEDULED';

CREATE INDEX idx_workflow_timers_workflow_id
    ON workflow_timers (workflow_id);

-- cycle 4: supports reminder cancel-cascade when a task reaches a terminal status
CREATE INDEX idx_workflow_timers_task_reminders
    ON workflow_timers (task_id, status)
    WHERE purpose = 'TASK_REMINDER' AND status = 'SCHEDULED';

-- Human tasks: one row per task, FK to workflow_instances (CASCADE delete).
-- assignee_type/key reflect the current (possibly reassigned) assignee.
-- decisions_snapshot_json carries the plan's decision options at creation time.
-- completed_by/cancelled_by/reassigned_by JSONB columns are NULL unless the transition occurred.
-- due_at + due_date_timer_id are NULL when no due-date was configured.
-- Lock-order invariant (cycle 3): workflow_timers → workflow_tasks → workflow_instances.
CREATE TABLE workflow_tasks (
    task_id              UUID         PRIMARY KEY,
    workflow_id          UUID         NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    step_id              VARCHAR(255) NOT NULL,
    status               TEXT         NOT NULL CHECK (status IN ('OPEN','COMPLETED','CANCELLED','EXPIRED')),
    -- Current queryable assignee snapshot. Full assignment history (creates and reassignments)
    -- lives immutably in workflow_history (TASK_CREATED / TASK_REASSIGNED entries). Kept as
    -- separate columns (rather than JSONB) because cycle 3 needs efficient task-list queries by
    -- assignee target — see idx_workflow_tasks_open_by_user/_role/_queue below.
    assignee_type        TEXT         NOT NULL CHECK (assignee_type IN ('USER','ROLE','QUEUE')),
    assignee_key         VARCHAR(255) NOT NULL,
    decisions_snapshot_json JSONB     NOT NULL,
    decision_name        VARCHAR(64)  NULL,
    decision_payload_json JSONB       NULL,
    -- Actor audit snapshots: serialized WorkflowActor as `{"kind": "...", "value": "..."}`.
    -- JSONB rather than split kind/value column pairs because cycle 3 has no SQL queries that
    -- index or filter by actor identity — actors are pure audit metadata.
    completed_by         JSONB        NULL,
    cancelled_by         JSONB        NULL,
    reassigned_by        JSONB        NULL,
    reassignment_reason  TEXT         NULL,
    due_at               TIMESTAMPTZ  NULL,
    due_date_timer_id    UUID         NULL REFERENCES workflow_timers(timer_id) ON DELETE SET NULL,
    completed_at         TIMESTAMPTZ  NULL,
    cancelled_at         TIMESTAMPTZ  NULL,
    expired_at           TIMESTAMPTZ  NULL,
    cancellation_reason  TEXT         NULL,
    -- cycle 4: durable reminder-fire counter. Incremented atomically when a TASK_REMINDER timer
    -- fires for this task; replaces the (slow + fragile) JSONB-path COUNT(*) over workflow_history
    -- the engine used in the first cycle-4 cut. Single SQL UPDATE returns the new count, which the
    -- engine uses as the 1-based reminderIndex for the TASK_REMINDER_FIRED history entry and the
    -- TASK_REMINDER event attribute.
    reminders_fired_count INTEGER     NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_workflow_tasks_workflow_id   ON workflow_tasks (workflow_id);
CREATE INDEX idx_workflow_tasks_open_by_user  ON workflow_tasks (assignee_key, status)
    WHERE assignee_type = 'USER'  AND status = 'OPEN';
CREATE INDEX idx_workflow_tasks_open_by_role  ON workflow_tasks (assignee_key, status)
    WHERE assignee_type = 'ROLE'  AND status = 'OPEN';
CREATE INDEX idx_workflow_tasks_open_by_queue ON workflow_tasks (assignee_key, status)
    WHERE assignee_type = 'QUEUE' AND status = 'OPEN';
CREATE INDEX idx_workflow_tasks_open_due_at   ON workflow_tasks (due_at)
    WHERE status = 'OPEN' AND due_at IS NOT NULL;
CREATE INDEX idx_workflow_tasks_updated_at_keyset ON workflow_tasks (updated_at DESC, task_id ASC);

-- ────────────────────────────────────────────────────────────────────
-- PRD-WF-002: Durable parallel fan-out / fan-in
-- ────────────────────────────────────────────────────────────────────
--
-- Adds two new tables and three nullable columns to existing tables to support
-- branch tokens and fan-in joins. Purely additive: existing single-path
-- workflows continue to work unchanged (NFR-WF-PAR-005). Branch tokens have no
-- FK from workflow_tasks/workflow_timers to workflow_branch_tokens to preserve
-- the existing lock-order invariant (workflow_timers → workflow_tasks →
-- workflow_instances); CASCADE cleanup still flows through workflow_id.
--
-- workflow_dedup gains a new kind 'dispatch' (8 chars; fits VARCHAR(16)) for
-- branch-level service-dispatch idempotency. Encoded by WorkflowDedupScopes:
--   kind='dispatch'
--   scope=workflowId.toString()                       (UUID, 36 chars)
--   key=SHA-256 hex of (forkStepId|US|branchId|US|stepId|US|targetId|US|operation)  (64 chars)
-- The engine inserts the dedup row inside BranchTransitionEngine BEFORE invoking
-- RecorderRouter; ON CONFLICT (kind, scope, key) DO NOTHING gates duplicate
-- dispatches across branch retries (FR-WF-PAR-038). No schema change required —
-- the existing (kind, scope, key) PRIMARY KEY admits arbitrary kinds.

-- ────────────────────────────────────────────────────────────────────
-- workflow_branch_tokens — one row per fork-group branch
-- ────────────────────────────────────────────────────────────────────
-- metadata carries durable propagation context (FR-CTX-178) captured at branch-create
-- time; persisted so the recovery sweep can rebind context per branch without
-- re-executing the capture path.
CREATE TABLE workflow_branch_tokens (
    id                 UUID PRIMARY KEY,
    workflow_id        UUID NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    fork_step_id       VARCHAR(255) NOT NULL,
    branch_id          VARCHAR(255) NOT NULL,
    current_step_id    VARCHAR(255) NOT NULL,
    -- Mirrors BranchStatus.name(); 32 chars covers the longest value
    -- (RETRY_SCHEDULED = 15) with headroom for additions.
    status             VARCHAR(32) NOT NULL,
    wait_type          VARCHAR(32),
    wait_key           VARCHAR(255),
    wait_aux_id        UUID,
    -- Branch-local result payload (consumed by the join's reducer);
    -- null when the branch did not produce one or terminated abnormally.
    result_json        JSONB,
    error_type         VARCHAR(255),
    error_message      TEXT,
    attempt_count      INTEGER NOT NULL DEFAULT 0,
    max_attempts       INTEGER NOT NULL,
    next_retry_at      TIMESTAMPTZ,
    last_error_type    VARCHAR(255),
    last_error_message TEXT,
    last_error_at      TIMESTAMPTZ,
    -- Optimistic-concurrency token; CAS-incremented on every branch update.
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- durable propagation context (FR-CTX-178); NULL ≡ empty map
    metadata           JSONB NULL,
    UNIQUE (workflow_id, fork_step_id, branch_id)
);

CREATE INDEX idx_workflow_branch_tokens_status_lookup
    ON workflow_branch_tokens (workflow_id, fork_step_id, status);

-- Recovery sweep selects RETRY_SCHEDULED branches whose next_retry_at is due
-- and stale RUNNING branches; partial index keeps it small.
CREATE INDEX idx_workflow_branch_tokens_recovery
    ON workflow_branch_tokens (status, next_retry_at)
    WHERE status IN ('RETRY_SCHEDULED', 'RUNNING');

-- Branch-aware signal/timer/task wait lookup; partial index excludes idle rows.
CREATE INDEX idx_workflow_branch_tokens_wait
    ON workflow_branch_tokens (wait_type, wait_key)
    WHERE wait_type IS NOT NULL;

-- ────────────────────────────────────────────────────────────────────
-- workflow_join_states — one row per fan-in join
-- ────────────────────────────────────────────────────────────────────
CREATE TABLE workflow_join_states (
    workflow_id       UUID NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    fork_step_id      VARCHAR(255) NOT NULL,
    join_step_id      VARCHAR(255) NOT NULL,
    -- Mirrors JoinPolicyType.name(); FIRST_FAILURE is the longest at 13 chars.
    policy            VARCHAR(32) NOT NULL,
    -- Mirrors JoinStateStatus.name(); 16 chars covers OPEN/COMPLETED/FAILED.
    status            VARCHAR(16) NOT NULL,
    winning_branch_id VARCHAR(255),
    decided_at        TIMESTAMPTZ,
    -- CAS-incremented on each update; the unique-writer guarantee for the
    -- join decision (FAN_IN_COMPLETED / FAN_IN_FAILED entries appended exactly
    -- once by the winning CAS).
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (workflow_id, fork_step_id, join_step_id)
);

CREATE INDEX idx_workflow_join_states_status ON workflow_join_states (status);

-- ────────────────────────────────────────────────────────────────────
-- workflow_tasks — branch-identity columns (nullable for back-compat)
-- ────────────────────────────────────────────────────────────────────
ALTER TABLE workflow_tasks
    ADD COLUMN branch_token_id UUID NULL,
    ADD COLUMN fork_step_id    VARCHAR(255) NULL,
    ADD COLUMN branch_id       VARCHAR(255) NULL;

CREATE INDEX idx_workflow_tasks_branch_token
    ON workflow_tasks (branch_token_id)
    WHERE branch_token_id IS NOT NULL;

-- ────────────────────────────────────────────────────────────────────
-- workflow_timers — branch-identity columns (nullable for back-compat)
-- ────────────────────────────────────────────────────────────────────
ALTER TABLE workflow_timers
    ADD COLUMN branch_token_id UUID NULL,
    ADD COLUMN fork_step_id    VARCHAR(255) NULL,
    ADD COLUMN branch_id       VARCHAR(255) NULL;

CREATE INDEX idx_workflow_timers_branch_token
    ON workflow_timers (branch_token_id)
    WHERE branch_token_id IS NOT NULL;

-- ────────────────────────────────────────────────────────────────────
-- Cycle 5 — Phase 2: object-centric workflows (FR-WF-120..127)
-- ────────────────────────────────────────────────────────────────────

-- Partial composite index for subject-version queries (FR-WF-123).
-- Most instances do not carry a subject_version. The existing
-- idx_workflow_instances_subject (subject_type, subject_id) covers type+id queries
-- already; this index is purely for the version-aware path.
CREATE INDEX idx_workflow_instances_subject_versioned
    ON workflow_instances (subject_type, subject_id, subject_version)
    WHERE subject_version IS NOT NULL;

-- Snapshot column populated at task creation (FR-WF-124).
-- Per-task semantics: a NULL snapshot is fine for tasks where requireVersionStability=false;
-- for stability-required tasks the engine fails the transition with
-- WorkflowSubjectVersionUnavailableException, the tx rolls back, and the workflow stays
-- in its prior step. See ADR-0055.
ALTER TABLE workflow_tasks ADD COLUMN subject_version_at_creation VARCHAR(64) NULL;
