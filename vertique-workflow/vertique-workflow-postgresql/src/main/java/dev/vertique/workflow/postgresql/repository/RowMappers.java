// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.RowMapper;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.JoinPolicyType;
import dev.vertique.workflow.state.JoinState;
import dev.vertique.workflow.state.JoinStateStatus;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import io.vertx.sqlclient.Row;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Static factories for {@link RowMapper} instances that map PostgreSQL result rows to workflow
 * domain objects.
 *
 * <p>All column names correspond to the schema defined in
 * {@code db/migration/workflow/V1__create_workflow_tables.sql}. Nullable columns are handled
 * defensively — a {@code null} DB column yields a {@code null} Java field.
 *
 * <p>This class is package-private and shared by all workflow repository classes.
 */
class RowMappers {

    private RowMappers() {}

    // --- WorkflowInstance ---

    /**
     * Returns a {@link RowMapper} that maps a result-set row from the {@code workflow_instances}
     * table to a {@link WorkflowInstance}.
     *
     * <p>Nullable DB columns — {@code business_key}, {@code subject_type}, {@code subject_id},
     * {@code subject_version}, {@code wait_type}, {@code wait_key}, {@code wait_aux_id},
     * {@code error_type}, {@code error_message}, {@code completed_at} — are tolerated and map to
     * {@code null} Java fields. The {@code state_json} JSONB column is extracted as a
     * {@code String} via {@link Row#getValue}. A {@link WorkflowSubjectRef} is constructed only
     * when both {@code subject_type} and {@code subject_id} are non-null and non-blank;
     * a blank {@code subject_version} is normalized to {@code null}. The blank-tolerance
     * is intentional: the cycle-5 {@code WorkflowSubjectRef} compact constructor rejects blanks,
     * so legacy or operator-inserted rows with blank subject columns must not crash reads.
     *
     * @return a row mapper for {@link WorkflowInstance}
     */
    static RowMapper<WorkflowInstance> workflowInstance() {
        return row -> {
            WorkflowInstanceId id = new WorkflowInstanceId(row.getUUID("id"));
            String definitionId = row.getString("definition_id");
            long definitionVersion = row.getLong("definition_version");
            String planHash = row.getString("plan_hash");
            long version = row.getLong("version");
            WorkflowStatus status = WorkflowStatus.valueOf(row.getString("status"));
            String businessKey = row.getString("business_key");

            // Subject ref is read-tolerant: the cycle-5 WorkflowSubjectRef compact constructor
            // rejects blank type/id/version-when-set, but a row with blank columns (legacy data
            // or operator SQL) must not crash reads. Treat any blank component as null; if both
            // type and id are null/blank, leave subjectRef null entirely.
            String subjectType = row.getString("subject_type");
            String subjectId = row.getString("subject_id");
            String subjectVersion = row.getString("subject_version");
            WorkflowSubjectRef subjectRef = null;
            if (subjectType != null && !subjectType.isBlank() && subjectId != null && !subjectId.isBlank()) {
                subjectRef = new WorkflowSubjectRef(
                        subjectType,
                        subjectId,
                        (subjectVersion != null && !subjectVersion.isBlank()) ? subjectVersion : null);
            }

            String currentStepId = row.getString("current_step_id");
            String waitTypeStr = row.getString("wait_type");
            WaitType waitType = waitTypeStr != null ? WaitType.valueOf(waitTypeStr) : null;
            String waitKey = row.getString("wait_key");
            java.util.UUID waitAuxId = row.getUUID("wait_aux_id");
            String stateJson = extractJsonb(row, "state_json");
            String errorType = row.getString("error_type");
            String errorMessage = row.getString("error_message");

            return new WorkflowInstance(
                    id,
                    definitionId,
                    definitionVersion,
                    planHash,
                    version,
                    status,
                    businessKey,
                    subjectRef,
                    currentStepId,
                    waitType,
                    waitKey,
                    waitAuxId,
                    stateJson,
                    errorType,
                    errorMessage,
                    toInstant(row.getOffsetDateTime("created_at")),
                    toInstant(row.getOffsetDateTime("updated_at")),
                    row.getJsonObject("metadata") != null
                            ? DurableMetadata.fromCarrier(row.getJsonObject("metadata"))
                            : null);
        };
    }

    // --- WorkflowHistoryEntry ---

    /**
     * Returns a {@link RowMapper} that maps a result-set row from the {@code workflow_history}
     * table to a {@link WorkflowHistoryEntry}.
     *
     * <p>The {@code payload_json} JSONB column is extracted as a {@code String}. The
     * {@code workflow_id} column is mapped to a {@link WorkflowInstanceId} wrapper.
     *
     * @return a row mapper for {@link WorkflowHistoryEntry}
     */
    static RowMapper<WorkflowHistoryEntry> workflowHistoryEntry() {
        return row -> {
            UUID workflowUuid = row.getUUID("workflow_id");
            WorkflowInstanceId instanceId = new WorkflowInstanceId(workflowUuid);
            long sequence = row.getLong("sequence");
            WorkflowEntryType entryType = WorkflowEntryType.valueOf(row.getString("entry_type"));
            String payloadJson = extractJsonb(row, "payload_json");
            return new WorkflowHistoryEntry(
                    instanceId, sequence, entryType, payloadJson, toInstant(row.getOffsetDateTime("recorded_at")));
        };
    }

    // --- BranchToken (PRD-WF-002) ---

    /**
     * Returns a {@link RowMapper} that maps a row from {@code workflow_branch_tokens} to a
     * {@link BranchToken}.
     *
     * @return a row mapper for {@link BranchToken}
     */
    static RowMapper<BranchToken> branchToken() {
        return row -> {
            String waitTypeStr = row.getString("wait_type");
            return new BranchToken(
                    row.getUUID("id"),
                    new WorkflowInstanceId(row.getUUID("workflow_id")),
                    row.getString("fork_step_id"),
                    row.getString("branch_id"),
                    row.getString("current_step_id"),
                    BranchStatus.valueOf(row.getString("status")),
                    waitTypeStr != null ? WaitType.valueOf(waitTypeStr) : null,
                    row.getString("wait_key"),
                    row.getUUID("wait_aux_id"),
                    extractJsonb(row, "result_json"),
                    row.getString("error_type"),
                    row.getString("error_message"),
                    row.getInteger("attempt_count"),
                    row.getInteger("max_attempts"),
                    toInstant(row.getOffsetDateTime("next_retry_at")),
                    row.getString("last_error_type"),
                    row.getString("last_error_message"),
                    toInstant(row.getOffsetDateTime("last_error_at")),
                    row.getLong("version"),
                    toInstant(row.getOffsetDateTime("created_at")),
                    toInstant(row.getOffsetDateTime("updated_at")),
                    DurableMetadata.fromCarrier(row.getJsonObject("metadata")));
        };
    }

    // --- JoinState (PRD-WF-002) ---

    /**
     * Returns a {@link RowMapper} that maps a row from {@code workflow_join_states} to a
     * {@link JoinState}.
     *
     * @return a row mapper for {@link JoinState}
     */
    static RowMapper<JoinState> joinState() {
        return row -> new JoinState(
                new WorkflowInstanceId(row.getUUID("workflow_id")),
                row.getString("fork_step_id"),
                row.getString("join_step_id"),
                JoinPolicyType.valueOf(row.getString("policy")),
                JoinStateStatus.valueOf(row.getString("status")),
                row.getString("winning_branch_id"),
                toInstant(row.getOffsetDateTime("decided_at")),
                row.getLong("version"),
                toInstant(row.getOffsetDateTime("created_at")),
                toInstant(row.getOffsetDateTime("updated_at")));
    }

    // --- Internal helpers ---

    // mapMetadata removed — use DurableMetadata.fromCarrier(JsonObject) at each call site.

    /**
     * Extracts a JSONB column value as a {@code String}. The Vert.x pg-client returns JSONB
     * columns as {@link io.vertx.core.json.JsonObject} or {@link io.vertx.core.json.JsonArray};
     * falling back to {@link Row#getValue} and calling {@code toString()} handles all JSON shapes.
     *
     * @param row        the result row
     * @param columnName the JSONB column to extract
     * @return the JSON string, or {@code null} if the column value is null
     */
    private static String extractJsonb(Row row, String columnName) {
        Object value = row.getValue(columnName);
        return value != null ? value.toString() : null;
    }

    /**
     * Converts an {@link OffsetDateTime} (as returned by the Vert.x pg-client for
     * {@code TIMESTAMPTZ} columns) to a UTC {@link java.time.Instant}.
     *
     * @param odt the offset date-time, or {@code null}
     * @return the corresponding {@link java.time.Instant}, or {@code null}
     */
    private static java.time.Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.withOffsetSameInstant(ZoneOffset.UTC).toInstant() : null;
    }
}
