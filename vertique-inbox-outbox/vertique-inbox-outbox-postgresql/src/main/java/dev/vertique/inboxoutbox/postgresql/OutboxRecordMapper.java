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
import lombok.extern.slf4j.Slf4j;

/**
 * Maps a PostgreSQL {@link Row} from the {@code outbox} table to an {@link OutboxRecord} domain
 * object.
 *
 * <p>All timestamp-with-timezone columns are read as {@link OffsetDateTime} and converted to
 * {@link Instant}. Nullable timestamps return {@code null}. The JSONB {@code headers} column is
 * converted from a {@link JsonObject} to a {@code Map<String, String>} of which the
 * {@link OutboxRecord} takes its unmodifiable copy; a {@code null} or empty headers value maps to
 * an empty map, and an entry whose value is JSON {@code null} is dropped with a WARN. The JSONB {@code metadata} column is
 * deserialized via {@link OutboxMetadata#fromJson(JsonObject)}; a {@code null} column value
 * maps to {@link OutboxMetadata#empty()}.
 *
 * <p>This class is not instantiable — use the static factory {@link #fromRow(Row)}.
 */
@Slf4j
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
        Long id = row.getLong(COL_ID);
        return new OutboxRecord(
                id,
                row.getUUID(COL_CARRIER_ID),
                row.getString(COL_AGGREGATE_TYPE),
                row.getString(COL_AGGREGATE_ID),
                row.getString(COL_EVENT_TYPE),
                row.getString(COL_DESTINATION),
                DestinationType.of(row.getString(COL_DESTINATION_TYPE)),
                row.getJsonObject(COL_PAYLOAD),
                toHeadersMap(id, row.getJsonObject(COL_HEADERS)),
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
     * Converts a nullable JSONB headers object to a {@code Map<String, String>}.
     *
     * <p>All non-null entry values are coerced to strings via {@link String#valueOf(Object)}. An
     * entry whose stored value is JSON {@code null} has no value to deliver: it is left out of the
     * map — it does not become the text {@code "null"} — and one WARN names the entry id and the
     * header key, shown through {@link OutboxHeaderKeys#forDisplay(String)}. Returns {@link Map#of()} when {@code json} is {@code null} or empty.
     *
     * <p>The returned map is handed straight to the {@link OutboxRecord} constructor, which takes the
     * one unmodifiable copy; this method does not copy it a second time.
     *
     * @param entryId the outbox entry id of the row, used only in the WARN
     * @param json    the JSONB headers value from the database, or {@code null}
     * @return a map of header name to header value, owned by the caller
     */
    private static Map<String, String> toHeadersMap(Long entryId, JsonObject json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        json.forEach(entry -> {
            if (entry.getValue() == null) {
                log.warn(
                        "Outbox entry {} has a stored null value for header '{}'; the header is dropped",
                        entryId,
                        OutboxHeaderKeys.forDisplay(entry.getKey()));
            } else {
                map.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        });
        return map;
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
