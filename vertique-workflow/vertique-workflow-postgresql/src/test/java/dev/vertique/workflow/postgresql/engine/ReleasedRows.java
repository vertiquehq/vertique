// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Raw SQL rows in the shape the first released schema created: no scope columns, and a dedup table that still calls
 * its namespace column {@code scope}. Use them only on a database migrated to the first version.
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
}
