-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2

-- Job execution tracking
CREATE TABLE job_executions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          VARCHAR(255) NOT NULL,
    job_type        VARCHAR(20) NOT NULL,
    handler         VARCHAR(255) NOT NULL,
    queue           VARCHAR(255) NOT NULL DEFAULT 'default',
    state           VARCHAR(20) NOT NULL,
    payload         JSONB,
    progress        JSONB,
    parameters      JSONB,
    -- Durable context-propagation metadata (FR-CTX-177) stored as a DurableMetadata carrier:
    -- {"context": {"namespace": {...}, ...}}
    -- Captured at enqueue by DelayedJobService.toExecution via DurableMetadata.toCarrier()
    -- after DurableContextPropagator.mergeCaptured(..., DELAYED_JOB).
    -- Decoded at poll by DurableContextPropagator.decodeToDispatchContext(fromCarrier(...), DELAYED_JOB).
    metadata        JSONB,
    attempt         INTEGER NOT NULL DEFAULT 0,
    max_attempts    INTEGER NOT NULL DEFAULT 5,
    priority        INTEGER NOT NULL DEFAULT 0,
    scheduled_at    TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    locked_by       VARCHAR(255),
    last_error      TEXT,
    error_type      VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for job claiming (delayed-job polling): eligible ENQUEUED rows ordered by priority then schedule.
-- Note: the scheduled_at <= NOW() predicate is applied at query time, not in the index (NOW() is not immutable).
CREATE INDEX idx_job_executions_claim
    ON job_executions (queue, priority DESC, scheduled_at ASC)
    WHERE state = 'ENQUEUED';

-- Index for stuck-job detection: PROCESSING rows with stale heartbeat
CREATE INDEX idx_job_executions_heartbeat
    ON job_executions (updated_at)
    WHERE state = 'PROCESSING';

-- Index for dashboard queries
CREATE INDEX idx_job_executions_state ON job_executions (state, created_at DESC);
CREATE INDEX idx_job_executions_job_id ON job_executions (job_id, created_at DESC);

-- Job log entries
CREATE TABLE job_logs (
    id              BIGSERIAL PRIMARY KEY,
    execution_id    UUID NOT NULL REFERENCES job_executions(id) ON DELETE CASCADE,
    level           VARCHAR(5) NOT NULL,
    message         TEXT NOT NULL,
    logged_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_job_logs_execution ON job_logs (execution_id, logged_at);

-- Recurring cron schedule definitions (dashboard visibility).
-- handler is nullable because service: targets resolve addresses at dispatch time;
-- target is the explicit target-reference column used by the cron scheduler.
CREATE TABLE job_schedules (
    job_id          VARCHAR(255) PRIMARY KEY,
    cron_expression VARCHAR(100) NOT NULL,
    handler         VARCHAR(255),
    target          VARCHAR(512),
    execution_mode  VARCHAR(20) NOT NULL DEFAULT 'EVERY_INSTANCE',
    timezone        VARCHAR(50) NOT NULL DEFAULT 'UTC',
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    overlap_policy  VARCHAR(20) NOT NULL DEFAULT 'SKIP',
    max_attempts    INTEGER NOT NULL DEFAULT 3,
    tracked         BOOLEAN NOT NULL DEFAULT TRUE,
    last_fired_at   TIMESTAMPTZ,
    next_fire_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Deduplication for SINGLE_INSTANCE cron fires (only one node wins per fire time)
CREATE UNIQUE INDEX idx_job_executions_cron_dedup
    ON job_executions (job_id, scheduled_at)
    WHERE job_type = 'CRON' AND state NOT IN ('DEAD_LETTER', 'CANCELLED');

-- Node heartbeat for crash detection (one row per application instance)
CREATE TABLE job_server_heartbeats (
    server_id       VARCHAR(255) PRIMARY KEY,
    last_heartbeat  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
