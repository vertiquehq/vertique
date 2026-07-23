// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.job.Checkpoint;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Maps database {@link Row} instances to {@link JobExecution} and {@link Checkpoint} domain
 * objects.
 *
 * <p>This mapper handles all column extraction and null-safety for the {@code job_executions} and
 * {@code job_checkpoints} tables. JSONB columns are surfaced as {@link JsonObject}; callers that
 * need typed deserialization should further convert via {@code JsonObject.mapTo(Class)}.
 */
final class JobExecutionMapper {

    private JobExecutionMapper() {}

    // --- Column names ---

    static final String COL_ID = "id";
    static final String COL_JOB_ID = "job_id";
    static final String COL_JOB_TYPE = "job_type";
    static final String COL_HANDLER = "handler";
    static final String COL_QUEUE = "queue";
    static final String COL_STATE = "state";
    static final String COL_PAYLOAD = "payload";
    static final String COL_PROGRESS = "progress";
    static final String COL_PARAMETERS = "parameters";
    static final String COL_ATTEMPT = "attempt";
    static final String COL_MAX_ATTEMPTS = "max_attempts";
    static final String COL_PRIORITY = "priority";
    static final String COL_SCHEDULED_AT = "scheduled_at";
    static final String COL_STARTED_AT = "started_at";
    static final String COL_COMPLETED_AT = "completed_at";
    static final String COL_LOCKED_BY = "locked_by";
    static final String COL_LAST_ERROR = "last_error";
    static final String COL_ERROR_TYPE = "error_type";
    static final String COL_CREATED_AT = "created_at";
    static final String COL_UPDATED_AT = "updated_at";
    static final String COL_METADATA = "metadata";

    // --- Row mappers ---

    /**
     * Maps a {@link Row} from {@code job_executions} to a {@link JobExecution}.
     *
     * <p>The {@code payload} column is deserialized as a {@link JsonObject} (the opaque JSONB
     * value). Callers that know the concrete payload type should further call
     * {@code ((JsonObject) execution.payload()).mapTo(MyType.class)}.
     *
     * <p>The {@code parameters} column is read as a {@link JsonObject} and converted to a
     * {@link java.util.Map} for the {@link JobExecution#parameters()} field. Returns an empty map
     * when the column is {@code NULL}.
     *
     * @param row the database row to map
     * @return the mapped {@link JobExecution}
     */
    static JobExecution fromRow(Row row) {
        ProgressSnapshot progress = mapProgress(row.getJsonObject(COL_PROGRESS));
        java.util.Map<String, Object> parameters = mapParameters(row.getJsonObject(COL_PARAMETERS));
        DurableMetadata metadata = mapMetadata(row.getJsonObject(COL_METADATA));
        return new JobExecution(
                row.getUUID(COL_ID),
                row.getString(COL_JOB_ID),
                JobType.valueOf(row.getString(COL_JOB_TYPE)),
                row.getString(COL_HANDLER),
                row.getString(COL_QUEUE),
                JobState.valueOf(row.getString(COL_STATE)),
                row.getInteger(COL_ATTEMPT),
                row.getInteger(COL_MAX_ATTEMPTS),
                row.getJsonObject(COL_PAYLOAD),
                row.getInteger(COL_PRIORITY),
                row.getString(COL_LOCKED_BY),
                toInstant(row.getOffsetDateTime(COL_SCHEDULED_AT)),
                toInstant(row.getOffsetDateTime(COL_CREATED_AT)),
                toInstant(row.getOffsetDateTime(COL_STARTED_AT)),
                toInstant(row.getOffsetDateTime(COL_COMPLETED_AT)),
                row.getString(COL_LAST_ERROR),
                row.getString(COL_ERROR_TYPE),
                progress,
                parameters,
                null,
                metadata);
    }

    /**
     * Maps a {@link Row} from {@code job_checkpoints} to a {@link Checkpoint}.
     *
     * <p>The checkpoint {@code value} is returned as a {@link JsonObject}.
     *
     * @param key the checkpoint key (passed separately as it may be a filter parameter)
     * @param row the database row to map
     * @return the mapped {@link Checkpoint}
     */
    static Checkpoint checkpointFromRow(String key, Row row) {
        JsonObject value = row.getJsonObject("value");
        Instant updatedAt = toInstant(row.getOffsetDateTime("updated_at"));
        return new Checkpoint(key, value, updatedAt);
    }

    // --- Internal helpers ---

    /**
     * Converts an {@link OffsetDateTime} column value to an {@link Instant}, returning {@code null}
     * when the value is null (nullable timestamp columns).
     *
     * @param odt the offset date-time value from the driver, or {@code null}
     * @return the equivalent {@link Instant}, or {@code null}
     */
    private static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }

    /**
     * Maps a JSONB progress column to a {@link ProgressSnapshot}. Returns
     * {@link ProgressSnapshot#EMPTY} when the column is null.
     *
     * @param json the raw JSONB value, or {@code null}
     * @return the progress snapshot
     */
    private static ProgressSnapshot mapProgress(JsonObject json) {
        if (json == null) {
            return ProgressSnapshot.EMPTY;
        }
        return new ProgressSnapshot(
                json.getLong("total", 0L),
                json.getLong("succeeded", 0L),
                json.getLong("failed", 0L),
                json.getString("status"));
    }

    /**
     * Maps a JSONB parameters column to an unmodifiable {@link java.util.Map}. Returns an empty
     * map when the column is {@code NULL}.
     *
     * @param json the raw JSONB value, or {@code null}
     * @return the parameters map, never {@code null}
     */
    private static java.util.Map<String, Object> mapParameters(JsonObject json) {
        if (json == null) {
            return java.util.Map.of();
        }
        return json.getMap();
    }

    /**
     * Maps a JSONB metadata column to a {@link DurableMetadata} document. Returns
     * {@link DurableMetadata#empty()} when the column is {@code NULL} or absent.
     *
     * <p>The persisted shape is {@code {"context": {namespace: body, ...}}} — i.e. the carrier
     * wrapper produced by {@link DurableMetadata#toCarrier()}. {@link DurableMetadata#fromCarrier}
     * reads the {@code "context"} key and reconstructs the namespaced document.
     *
     * @param json the raw JSONB carrier value, or {@code null}
     * @return the reconstructed {@link DurableMetadata} document, never {@code null}
     */
    private static DurableMetadata mapMetadata(JsonObject json) {
        return DurableMetadata.fromCarrier(json);
    }
}
