// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Raw SQL rows in the shape the first released schema created: no scope columns, and a dedup table that still calls
 * its namespace column {@code scope}. Use them only on a database migrated to the first or second version; the timer, join and branch rows are
 * written in the shape the previous release stored them.
 */
final class ReleasedRows {

    private ReleasedRows() {}

    /**
     * Inserts an instance waiting on its open human task, with the task row carrying a one-decision snapshot.
     *
     * @param instanceId the instance id
     * @param taskId the task id, also the instance's wait key
     * @param planHash the plan hash the instance is pinned to
     * @param payloadTypeName the binary name of the payload class the stored decision names
     * @param nextStepId the step the stored decision leads to
     */
    static void instanceAtTask(
            Connection connection,
            String definitionId,
            UUID instanceId,
            UUID taskId,
            String planHash,
            String payloadTypeName,
            String nextStepId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_instances
                    (id, definition_id, definition_version, plan_hash, status, current_step_id, wait_type, wait_key,
                     state_json, created_at, updated_at)
                VALUES (?, ?, 1, ?, 'WAITING', 'review', 'TASK', ?, ?::jsonb,
                        '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z')
                """)) {
            insert.setObject(1, instanceId);
            insert.setString(2, definitionId);
            insert.setString(3, planHash);
            insert.setString(4, taskId.toString());
            insert.setString(5, "{\"id\":\"" + instanceId + "\",\"note\":\"started\"}");
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_tasks
                    (task_id, workflow_id, step_id, status, assignee_type, assignee_key, decisions_snapshot_json,
                     created_at, updated_at)
                VALUES (?, ?, 'review', 'OPEN', 'ROLE', 'reviewers', ?::jsonb,
                        '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z')
                """)) {
            insert.setObject(1, taskId);
            insert.setObject(2, instanceId);
            insert.setString(
                    3,
                    "[{\"name\":\"approve\",\"payloadTypeName\":\"" + payloadTypeName + "\",\"nextStepId\":\""
                            + nextStepId + "\"}]");
            insert.executeUpdate();
        }
    }

    /**
     * Inserts an instance waiting for a signal at the given step.
     *
     * @param instanceId the instance id
     * @param stepId the step that waits
     * @param signalName the signal the step waits for, also the instance's wait key
     * @param planHash the plan hash the instance is pinned to
     */
    static void instanceAtSignal(
            Connection connection,
            String definitionId,
            UUID instanceId,
            String stepId,
            String signalName,
            String planHash)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_instances
                    (id, definition_id, definition_version, plan_hash, status, current_step_id, wait_type, wait_key,
                     state_json, created_at, updated_at)
                VALUES (?, ?, 1, ?, 'WAITING', ?, 'SIGNAL', ?, ?::jsonb,
                        '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z')
                """)) {
            insert.setObject(1, instanceId);
            insert.setString(2, definitionId);
            insert.setString(3, planHash);
            insert.setString(4, stepId);
            insert.setString(5, signalName);
            insert.setString(6, "{\"id\":\"" + instanceId + "\",\"note\":\"started\"}");
            insert.executeUpdate();
        }
    }

    /**
     * Inserts the start dedup row of an instance: the definition id as the namespace and the version fingerprint a
     * start of version 1 recorded.
     *
     * @param definitionId the definition id, stored in the old {@code scope} column
     * @param idempotencyKey the idempotency key the start used
     * @param instanceId the instance the start created
     */
    static void startDedup(Connection connection, String definitionId, String idempotencyKey, UUID instanceId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_dedup (kind, scope, key, workflow_id, fingerprint)
                VALUES ('start', ?, ?, ?, 'definitionVersion=1')
                """)) {
            insert.setString(1, definitionId);
            insert.setString(2, idempotencyKey);
            insert.setObject(3, instanceId);
            insert.executeUpdate();
        }
    }

    /**
     * Inserts an instance waiting at a timer step; the instance's wait key is the timer id.
     *
     * @param instanceId the instance id
     * @param stepId the timer step
     * @param timerId the timer the instance waits for
     * @param planHash the plan hash the instance is pinned to
     */
    static void instanceAtTimer(
            Connection connection, String definitionId, UUID instanceId, String stepId, UUID timerId, String planHash)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_instances
                    (id, definition_id, definition_version, plan_hash, status, current_step_id, wait_type, wait_key,
                     state_json, created_at, updated_at)
                VALUES (?, ?, 1, ?, 'WAITING', ?, 'TIMER', ?, ?::jsonb,
                        '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z')
                """)) {
            insert.setObject(1, instanceId);
            insert.setString(2, definitionId);
            insert.setString(3, planHash);
            insert.setString(4, stepId);
            insert.setString(5, timerId.toString());
            insert.setString(6, "{\"id\":\"" + instanceId + "\",\"note\":\"started\"}");
            insert.executeUpdate();
        }
    }

    /**
     * Inserts a timer row in the previous schema's shape: backed by a delayed-job execution id and carrying no command.
     *
     * @param timerId the timer id
     * @param instanceId the instance the timer belongs to
     * @param stepId the step the timer was scheduled at
     * @param status the timer status, such as {@code SCHEDULED} or {@code FIRED}
     * @param fireAt the instant the timer fires
     * @param purpose the timer purpose, such as {@code STANDALONE} or {@code TASK_DUE}
     * @param taskId the task of a due-date or reminder timer; null otherwise
     * @param metadataJson the durable metadata carrier, or null for none
     */
    static void timer(
            Connection connection,
            UUID timerId,
            UUID instanceId,
            String stepId,
            String status,
            Instant fireAt,
            String purpose,
            UUID taskId,
            String metadataJson)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_timers
                    (timer_id, workflow_id, step_id, status, fire_at, delayed_job_execution_id, scheduled_at,
                     purpose, task_id, metadata)
                VALUES (?, ?, ?, ?, ?, ?, '2026-05-10T00:00:00Z', ?, ?, ?::jsonb)
                """)) {
            insert.setObject(1, timerId);
            insert.setObject(2, instanceId);
            insert.setString(3, stepId);
            insert.setString(4, status);
            insert.setTimestamp(5, Timestamp.from(fireAt));
            insert.setObject(6, UUID.randomUUID());
            insert.setString(7, purpose);
            insert.setObject(8, taskId);
            insert.setString(9, metadataJson);
            insert.executeUpdate();
        }
    }

    /** Gives an open task its due date and the timer that fires it. */
    static void dueDate(Connection connection, UUID taskId, UUID timerId, Instant dueAt) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE workflow_tasks SET due_at = ?, due_date_timer_id = ? WHERE task_id = ?")) {
            update.setTimestamp(1, Timestamp.from(dueAt));
            update.setObject(2, timerId);
            update.setObject(3, taskId);
            update.executeUpdate();
        }
    }

    /**
     * Inserts an instance parked at a join while its fork group runs, and the group's join state.
     *
     * @param instanceId the instance id
     * @param forkStepId the fork step
     * @param joinStepId the join step the instance waits at
     * @param joinPolicy the join policy name, such as {@code ALL_REQUIRED}
     * @param joinStatus the join state status, {@code OPEN} or {@code COMPLETED}
     * @param winningBranchId the branch that decided a completed join; null while open
     * @param planHash the plan hash the instance is pinned to
     */
    static void instanceAtJoin(
            Connection connection,
            String definitionId,
            UUID instanceId,
            String forkStepId,
            String joinStepId,
            String joinPolicy,
            String joinStatus,
            String winningBranchId,
            String planHash)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_instances
                    (id, definition_id, definition_version, plan_hash, status, current_step_id, wait_type, wait_key,
                     state_json, created_at, updated_at)
                VALUES (?, ?, 1, ?, 'WAITING', ?, 'JOIN', ?, ?::jsonb,
                        '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z')
                """)) {
            insert.setObject(1, instanceId);
            insert.setString(2, definitionId);
            insert.setString(3, planHash);
            insert.setString(4, joinStepId);
            insert.setString(5, forkStepId);
            insert.setString(6, "{\"id\":\"" + instanceId + "\"}");
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_join_states
                    (workflow_id, fork_step_id, join_step_id, policy, status, winning_branch_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setObject(1, instanceId);
            insert.setString(2, forkStepId);
            insert.setString(3, joinStepId);
            insert.setString(4, joinPolicy);
            insert.setString(5, joinStatus);
            insert.setString(6, winningBranchId);
            insert.executeUpdate();
        }
    }

    /**
     * Inserts a branch token of a running fork group.
     *
     * @param instanceId the instance the group belongs to
     * @param forkStepId the fork step
     * @param branchId the branch id
     * @param currentStepId the step the branch is at, which a retry runs again
     * @param status the branch status, such as {@code COMPLETED} or {@code RETRY_SCHEDULED}
     * @param nextRetryAt when a scheduled retry is due; null for none
     */
    static void branchToken(
            Connection connection,
            UUID instanceId,
            String forkStepId,
            String branchId,
            String currentStepId,
            String status,
            Instant nextRetryAt)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_branch_tokens
                    (id, workflow_id, fork_step_id, branch_id, current_step_id, status, attempt_count, max_attempts,
                     next_retry_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, 3, ?)
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, instanceId);
            insert.setString(3, forkStepId);
            insert.setString(4, branchId);
            insert.setString(5, currentStepId);
            insert.setString(6, status);
            insert.setTimestamp(7, nextRetryAt == null ? null : Timestamp.from(nextRetryAt));
            insert.executeUpdate();
        }
    }
}
