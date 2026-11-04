// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.db.query.OrderKey;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.query.OutboxEntryDetail;
import dev.vertique.inboxoutbox.query.OutboxEntryPayload;
import dev.vertique.inboxoutbox.query.OutboxEntrySummary;
import dev.vertique.inboxoutbox.query.OutboxPayloadTooLargeException;
import dev.vertique.inboxoutbox.query.OutboxQuery;
import dev.vertique.inboxoutbox.query.OutboxQueryConfig;
import dev.vertique.inboxoutbox.query.OutboxQueryService;
import dev.vertique.inboxoutbox.query.OutboxSort;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * PostgreSQL implementation of {@link OutboxQueryService}.
 *
 * <p>Every statement is built once at construction, and every table reference in it is qualified with the
 * schema mapped for the {@code inbox-outbox} instance when there is one, exactly as
 * {@link PgInboxOutboxRepository} does. The state filter is written into the statement as literals taken from
 * {@link OutboxEntryState} constants, never from caller text, so the planner can match the partial indexes of
 * the outbox table; every other filter is a bound parameter appended from a constant fragment that names no table.
 *
 * <p>Reads project only the columns they return: the list never selects payload, headers, metadata or the scope
 * label, and the detail reads at most {@value OutboxEntryDetail#MAX_LAST_ERROR_LENGTH} characters of the last
 * error from the database. {@link #getPayload(long)} decides the size limit in the same statement that reads the
 * content, so an oversized value is never transferred. The listing pages with the shared keyset paging of
 * {@code vertique-db}, ordered by one timestamp column and the entry id.
 *
 * <p>Database failures leave this class as an
 * {@link dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException} whose message carries no SQL, driver
 * text or payload.
 */
public class PgOutboxQueryService extends PgSqlRepository implements OutboxQueryService {

    private static final String SUMMARY_COLUMNS = "id, state, destination_type, destination, event_type,"
            + " aggregate_type, aggregate_id, attempt, max_attempts, error_type, created_at, updated_at";

    private final PgDbExceptionMapper failureMapper;
    private final int maxPayloadBytes;

    private final String sqlListPrefix;
    private final String sqlFind;
    private final String sqlReadPayload;

    /**
     * Creates the service, qualifying every table reference with the schema mapped to the {@code inbox-outbox}
     * instance when {@code schemas} holds one.
     *
     * @param pool            the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL failure mapper for exception translation
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     * @param config          the query configuration (payload size limit)
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment PostgreSQL
     *                                  identifier
     */
    public PgOutboxQueryService(
            Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas, OutboxQueryConfig config) {
        super(Objects.requireNonNull(pool, "pool"), Objects.requireNonNull(exceptionMapper, "exceptionMapper"));
        this.failureMapper = exceptionMapper;
        this.maxPayloadBytes = Objects.requireNonNull(config, "config").maxPayloadBytes();

        String outbox = PgTableNames.qualify(schemas, InboxOutboxMigrationsModule.INSTANCE_NAME, "outbox");

        this.sqlListPrefix = "SELECT " + SUMMARY_COLUMNS + " FROM " + outbox + " WHERE ";

        int max = OutboxEntryDetail.MAX_LAST_ERROR_LENGTH;
        this.sqlFind = "SELECT " + SUMMARY_COLUMNS + ", available_at, scheduled_at, published_at, claimed_at,"
                + " claimed_by, left(last_error, " + max + ") AS last_error,"
                + " COALESCE(char_length(last_error) > " + max + ", false) AS last_error_truncated"
                + " FROM " + outbox + " WHERE id = $1";

        // One statement measures the combined UTF-8 size and returns the text only when it fits, so a large value
        // never leaves the database and no separate size check can race with the read. The JSONB-to-text
        // conversions run once, in the materialized CTE; the size and both CASE expressions read their result.
        this.sqlReadPayload = "WITH content AS MATERIALIZED (SELECT payload::text AS payload_text,"
                + " headers::text AS headers_text FROM " + outbox + " WHERE id = $1),"
                + " sized AS (SELECT payload_text, headers_text,"
                + " octet_length(payload_text) + COALESCE(octet_length(headers_text), 0) AS content_bytes"
                + " FROM content)"
                + " SELECT content_bytes,"
                + " CASE WHEN content_bytes <= $2 THEN payload_text END AS payload_text,"
                + " CASE WHEN content_bytes <= $2 THEN headers_text END AS headers_text FROM sized";
    }

    @Override
    public Future<PagedResult<OutboxEntrySummary>> list(OutboxQuery query, PageCursor cursor) {
        if (query == null || cursor == null) {
            return Future.failedFuture(new ValidationException("query and cursor must not be null"));
        }
        List<Object> keyset = cursor.keysetValues();
        if (!keyset.isEmpty()
                && !(keyset.size() == 2 && keyset.get(0) instanceof OffsetDateTime && keyset.get(1) instanceof Long)) {
            return Future.failedFuture(new ValidationException("cursor does not belong to an outbox listing"));
        }
        PageCursor page;
        try {
            page = cursor.validatePageSize(1, MAX_PAGE_SIZE);
        } catch (ValidationException e) {
            return Future.failedFuture(new ValidationException("page size must be between 1 and " + MAX_PAGE_SIZE));
        }

        StringBuilder sql = new StringBuilder(sqlListPrefix).append(stateFilter(query));
        Tuple params = Tuple.tuple();
        if (query.destinationType() != null) {
            filter(
                    sql,
                    params,
                    " AND destination_type = $",
                    query.destinationType().id());
        }
        if (query.destination() != null) {
            filter(sql, params, " AND destination = $", query.destination());
        }
        if (query.eventType() != null) {
            filter(sql, params, " AND event_type = $", query.eventType());
        }
        if (query.aggregateType() != null) {
            filter(sql, params, " AND aggregate_type = $", query.aggregateType());
        }
        if (query.aggregateId() != null) {
            filter(sql, params, " AND aggregate_id = $", query.aggregateId());
        }
        if (query.createdFrom() != null) {
            filter(sql, params, " AND created_at >= $", toOffset(query.createdFrom()));
        }
        if (query.createdBefore() != null) {
            filter(sql, params, " AND created_at < $", toOffset(query.createdBefore()));
        }
        if (query.updatedFrom() != null) {
            filter(sql, params, " AND updated_at >= $", toOffset(query.updatedFrom()));
        }
        if (query.updatedBefore() != null) {
            filter(sql, params, " AND updated_at < $", toOffset(query.updatedBefore()));
        }

        OutboxSort sort = query.sort();
        OrderKey primary = sort.ascending() ? OrderKey.asc(sort.column()) : OrderKey.desc(sort.column());
        OrderKey tiebreaker = sort.ascending() ? OrderKey.asc("id") : OrderKey.desc("id");
        return this.<OutboxEntrySummary>pagedQuery(sql.toString())
                .params(params)
                .mapping(PgOutboxQueryService::toSummary)
                .orderBy(primary, tiebreaker)
                .uniqueKey("id")
                .page(page)
                .recover(t -> Future.failedFuture(PgOutboxFailures.translate(failureMapper, t, "outbox entry list")));
    }

    @Override
    public Future<Optional<OutboxEntryDetail>> getById(long entryId) {
        if (entryId <= 0) {
            return Future.failedFuture(new ValidationException("entryId must be positive"));
        }
        return pool.preparedQuery(sqlFind)
                .execute(Tuple.of(entryId))
                .map(rows -> rows.size() == 0
                        ? Optional.<OutboxEntryDetail>empty()
                        : Optional.of(toDetail(rows.iterator().next())))
                .recover(t -> Future.failedFuture(PgOutboxFailures.translate(failureMapper, t, "outbox entry get")));
    }

    @Override
    public Future<Optional<OutboxEntryPayload>> getPayload(long entryId) {
        if (entryId <= 0) {
            return Future.failedFuture(new ValidationException("entryId must be positive"));
        }
        return pool.preparedQuery(sqlReadPayload)
                .execute(Tuple.of(entryId, (long) maxPayloadBytes))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return Optional.<OutboxEntryPayload>empty();
                    }
                    Row row = rows.iterator().next();
                    if (row.getLong("content_bytes") > maxPayloadBytes) {
                        throw new OutboxPayloadTooLargeException();
                    }
                    return Optional.of(new OutboxEntryPayload(
                            entryId, row.getString("payload_text"), toHeaders(row.getString("headers_text"))));
                })
                .recover(
                        t -> Future.failedFuture(PgOutboxFailures.translate(failureMapper, t, "outbox entry payload")));
    }

    // --- Mapping helpers ---

    /** The state predicate, written from enum constants so it is a literal the planner can match to an index. */
    private static String stateFilter(OutboxQuery query) {
        if (query.states().size() == 1) {
            return "state = '" + query.states().iterator().next().name() + "'";
        }
        return query.states().stream()
                .map(state -> "'" + state.name() + "'")
                .collect(Collectors.joining(", ", "state IN (", ")"));
    }

    private static void filter(StringBuilder sql, Tuple params, String fragment, Object value) {
        params.addValue(value);
        sql.append(fragment).append(params.size());
    }

    private static OutboxEntrySummary toSummary(Row row) {
        return new OutboxEntrySummary(
                row.getLong("id"),
                OutboxEntryState.valueOf(row.getString("state")),
                DestinationType.of(row.getString("destination_type")),
                row.getString("destination"),
                row.getString("event_type"),
                row.getString("aggregate_type"),
                row.getString("aggregate_id"),
                row.getInteger("attempt"),
                row.getInteger("max_attempts"),
                row.getString("error_type"),
                row.getOffsetDateTime("created_at").toInstant(),
                row.getOffsetDateTime("updated_at").toInstant());
    }

    private static OutboxEntryDetail toDetail(Row row) {
        return new OutboxEntryDetail(
                row.getLong("id"),
                OutboxEntryState.valueOf(row.getString("state")),
                DestinationType.of(row.getString("destination_type")),
                row.getString("destination"),
                row.getString("event_type"),
                row.getString("aggregate_type"),
                row.getString("aggregate_id"),
                row.getInteger("attempt"),
                row.getInteger("max_attempts"),
                row.getString("error_type"),
                row.getOffsetDateTime("created_at").toInstant(),
                row.getOffsetDateTime("updated_at").toInstant(),
                row.getOffsetDateTime("available_at").toInstant(),
                instantOrNull(row.getOffsetDateTime("scheduled_at")),
                instantOrNull(row.getOffsetDateTime("published_at")),
                instantOrNull(row.getOffsetDateTime("claimed_at")),
                row.getString("claimed_by"),
                row.getString("last_error"),
                row.getBoolean("last_error_truncated"));
    }

    private static Instant instantOrNull(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** Parses the stored application headers; a header whose stored value is JSON null has no value and is left out. */
    private static Map<String, String> toHeaders(String headersText) {
        if (headersText == null) {
            return Map.of();
        }
        JsonObject json = new JsonObject(headersText);
        Map<String, String> headers = new LinkedHashMap<>();
        json.forEach(entry -> {
            if (entry.getValue() != null) {
                headers.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        });
        return headers;
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
