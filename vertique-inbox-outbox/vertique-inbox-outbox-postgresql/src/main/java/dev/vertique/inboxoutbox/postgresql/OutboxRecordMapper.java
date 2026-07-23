// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxRecord;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a PostgreSQL {@link Row} from the {@code outbox} table to an {@link OutboxRecord} domain
 * object.
 *
 * <p>All timestamp-with-timezone columns are read as {@link OffsetDateTime} and converted to
 * {@link Instant}. Nullable timestamps return {@code null}. The JSONB {@code headers} column is
 * converted from a {@link JsonObject} to an immutable {@code Map<String, String>}; a {@code null}
 * or empty headers value maps to {@link Map#of()}. The JSONB {@code metadata} column is
 * deserialized via {@link OutboxMetadata#fromJson(JsonObject)}; a {@code null} column value
 * maps to {@link OutboxMetadata#empty()}.
 *
 * <p>This class is not instantiable — use the static factory {@link #fromRow(Row)}.
 */
final class OutboxRecordMapper {

    /** Prevent instantiation. */
    private OutboxRecordMapper() {}

    // --- Column names ---

    static final String COL_ID = "id";
    static final String COL_CARRIER_ID = "carrier_id";
    static final String COL_AGGREGATE_TYPE = "aggregate_type";
    static final String COL_AGGREGATE_ID = "aggregate_id";
    static final String COL_EVENT_TYPE = "event_type";
    static final String COL_DESTINATION = "destination";
    static final String COL_DESTINATION_TYPE = "destination_type";
    static final String COL_PAYLOAD = "payload";
    static final String COL_HEADERS = "headers";
    static final String COL_METADATA = "metadata";
    static final String COL_SCHEDULED_AT = "scheduled_at";
    static final String COL_AVAILABLE_AT = "available_at";
    static final String COL_STATE = "state";
    static final String COL_ATTEMPT = "attempt";
    static final String COL_MAX_ATTEMPTS = "max_attempts";
    static final String COL_CLAIMED_AT = "claimed_at";
    static final String COL_CLAIMED_BY = "claimed_by";
    static final String COL_PUBLISHED_AT = "published_at";
    static final String COL_LAST_ERROR = "last_error";
    static final String COL_ERROR_TYPE = "error_type";
    static final String COL_CREATED_AT = "created_at";
    static final String COL_UPDATED_AT = "updated_at";

    /**
     * Maps a single {@link Row} from the {@code outbox} table to an {@link OutboxRecord}.
     *
     * @param row the result row from the PostgreSQL client; must contain all {@code outbox} columns
     * @return the mapped {@link OutboxRecord} domain object
     */
    static OutboxRecord fromRow(Row row) {
        return new OutboxRecord(
                row.getLong(COL_ID),
                row.getUUID(COL_CARRIER_ID),
                row.getString(COL_AGGREGATE_TYPE),
                row.getString(COL_AGGREGATE_ID),
                row.getString(COL_EVENT_TYPE),
                row.getString(COL_DESTINATION),
                DestinationType.of(row.getString(COL_DESTINATION_TYPE)),
                row.getJsonObject(COL_PAYLOAD),
                toHeadersMap(row.getJsonObject(COL_HEADERS)),
                OutboxMetadata.fromJson(row.getJsonObject(COL_METADATA)),
                toInstant(row.getOffsetDateTime(COL_SCHEDULED_AT)),
                toInstant(row.getOffsetDateTime(COL_AVAILABLE_AT)),
                OutboxEntryState.valueOf(row.getString(COL_STATE)),
                row.getInteger(COL_ATTEMPT),
                row.getInteger(COL_MAX_ATTEMPTS),
                toInstant(row.getOffsetDateTime(COL_CLAIMED_AT)),
                row.getString(COL_CLAIMED_BY),
                toInstant(row.getOffsetDateTime(COL_PUBLISHED_AT)),
                row.getString(COL_LAST_ERROR),
                row.getString(COL_ERROR_TYPE),
                toInstant(row.getOffsetDateTime(COL_CREATED_AT)),
                toInstant(row.getOffsetDateTime(COL_UPDATED_AT)));
    }

    /**
     * Converts a nullable JSONB headers object to an immutable {@code Map<String, String>}.
     *
     * <p>All entry values are coerced to strings via {@link String#valueOf(Object)}. Returns
     * {@link Map#of()} when {@code json} is {@code null} or empty.
     *
     * @param json the JSONB headers value from the database, or {@code null}
     * @return an immutable map of header name to header value
     */
    private static Map<String, String> toHeadersMap(JsonObject json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        json.forEach(entry -> map.put(entry.getKey(), String.valueOf(entry.getValue())));
        return Map.copyOf(map);
    }

    /**
     * Converts a nullable {@link OffsetDateTime} to an {@link Instant}.
     *
     * @param odt the offset date-time to convert, or {@code null}
     * @return the corresponding {@link Instant}, or {@code null} if the input is {@code null}
     */
    private static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }
}
