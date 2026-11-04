// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.admin.InboxOutboxAdminConfig;
import dev.vertique.inboxoutbox.admin.OutboxAdminHistoryKey;
import dev.vertique.inboxoutbox.admin.OutboxAdminHistoryQuery;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterKey;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterQuery;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Statement-shape proofs that need no database: schema qualification of every administration statement under a
 * mapping, narrow projections, and the shape of the safe dead-letter cleanup. The behaviour behind these shapes is
 * proved against PostgreSQL in the integration tests.
 */
@DisplayName("administration SQL shape")
class PgOutboxDeadLetterServiceSqlTest {

    private static final String SCHEMA = "messaging";
    private static final Pattern TABLE_REFERENCE = Pattern.compile("(?i)\\b(?:FROM|INTO|UPDATE|JOIN)\\s+(\\S+)");
    private static final List<String> NEVER_SELECTED =
            List.of("payload", "headers", "metadata", "scope_key", "carrier_id", "claimed_by");

    private static PgOutboxDeadLetterService service(Pool pool, DbSchemas schemas) {
        return new PgOutboxDeadLetterService(
                pool,
                new PgDbExceptionMapper(),
                schemas,
                InboxOutboxAdminConfig.builder().build());
    }

    private static Map<String, String> statements(Object target) throws IllegalAccessException {
        Map<String, String> found = new java.util.TreeMap<>();
        for (Field field : target.getClass().getDeclaredFields()) {
            if (field.getType() == String.class && field.getName().startsWith("sql")) {
                field.setAccessible(true);
                found.put(field.getName(), (String) field.get(target));
            }
        }
        return found;
    }

    private static void assertEveryTableIsQualified(String name, String sql) {
        Matcher matcher = TABLE_REFERENCE.matcher(sql);
        while (matcher.find()) {
            String reference = matcher.group(1);
            boolean qualified = reference.startsWith("\"" + SCHEMA + "\".\"outbox");
            boolean cteReference = List.of("batch", "content", "sized").stream()
                    .anyMatch(cte -> reference.equals(cte) || reference.startsWith(cte + ")"));
            assertTrue(qualified || cteReference, name + " references an unqualified table: " + reference);
        }
    }

    @Test
    @DisplayName("every constructor-built statement qualifies the outbox and receipt tables with the mapped schema")
    void constructorStatementsAreQualified() throws Exception {
        PgOutboxDeadLetterService mapped = service(mock(Pool.class), new DbSchemas(Map.of("inbox-outbox", SCHEMA)));

        Map<String, String> statements = statements(mapped);

        assertTrue(statements.size() >= 12, statements.keySet().toString());
        for (Map.Entry<String, String> statement : statements.entrySet()) {
            assertEveryTableIsQualified(statement.getKey(), statement.getValue());
        }
        // each statement that touches a table names one of the two administration tables
        assertTrue(statements.get("sqlReserve").contains("\"messaging\".\"outbox_admin_operations\""));
        assertTrue(statements.get("sqlLockTarget").contains("\"messaging\".\"outbox\""));
        assertTrue(statements.get("sqlRetry").contains("\"messaging\".\"outbox\""));
        assertTrue(statements.get("sqlDiscard").contains("\"messaging\".\"outbox\""));
        assertTrue(statements.get("sqlComplete").contains("\"messaging\".\"outbox_admin_operations\""));
        assertTrue(statements.get("sqlHistory").contains("\"messaging\".\"outbox_admin_operations\""));
        assertTrue(statements.get("sqlCleanupOperations").contains("\"messaging\".\"outbox_admin_operations\""));
    }

    @Test
    @DisplayName("without a mapping the statements name the tables bare, as the repository does")
    void unmappedStatementsAreBare() throws Exception {
        PgOutboxDeadLetterService unmapped = service(mock(Pool.class), DbSchemas.empty());

        for (Map.Entry<String, String> statement : statements(unmapped).entrySet()) {
            assertFalse(statement.getValue().contains("\"messaging\""), statement.getKey());
        }
        assertTrue(statements(unmapped).get("sqlReserve").contains("INSERT INTO outbox_admin_operations "));
    }

    @Test
    @DisplayName("the listing statement is qualified with every optional filter and the keyset applied")
    @SuppressWarnings("unchecked")
    void listStatementWithAllFiltersIsQualified() {
        Pool pool = mock(Pool.class);
        PreparedQuery<RowSet<Row>> query = mock(PreparedQuery.class);
        when(pool.preparedQuery(anyString())).thenReturn(query);
        when(query.execute(any(Tuple.class))).thenReturn(Future.failedFuture(new RuntimeException("stop")));
        PgOutboxDeadLetterService mapped = service(pool, new DbSchemas(Map.of("inbox-outbox", SCHEMA)));
        Instant at = Instant.parse("2026-01-01T00:00:00Z");

        mapped.list(OutboxDeadLetterQuery.builder()
                .entryId(5L)
                .destinationType(DestinationType.KAFKA)
                .destination("d")
                .eventType("e")
                .aggregateType("at")
                .aggregateId("ai")
                .errorType("et")
                .failedFrom(at)
                .failedBefore(at.plusSeconds(1))
                .createdFrom(at)
                .createdBefore(at.plusSeconds(1))
                .limit(10)
                .after(new OutboxDeadLetterKey(at, 9))
                .build());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pool).preparedQuery(sql.capture());
        String text = sql.getValue();
        assertEveryTableIsQualified("list", text);
        assertTrue(text.contains("FROM \"messaging\".\"outbox\" WHERE state = 'DEAD_LETTER'"), text);
        assertTrue(text.contains("(updated_at, id) < ($"), text);
        assertTrue(text.endsWith(" ORDER BY updated_at DESC, id DESC LIMIT $14"), text);
        for (String column : NEVER_SELECTED) {
            assertFalse(text.contains(column), "the listing must not reference " + column + ": " + text);
        }
        assertFalse(text.contains("'d'") || text.contains("'e'"), "filter values are bound, never inlined: " + text);
    }

    @Test
    @DisplayName("the history statement with a keyset is qualified and bound")
    @SuppressWarnings("unchecked")
    void historyStatementIsQualified() {
        Pool pool = mock(Pool.class);
        PreparedQuery<RowSet<Row>> query = mock(PreparedQuery.class);
        when(pool.preparedQuery(anyString())).thenReturn(query);
        when(query.execute(any(Tuple.class))).thenReturn(Future.failedFuture(new RuntimeException("stop")));
        PgOutboxDeadLetterService mapped = service(pool, new DbSchemas(Map.of("inbox-outbox", SCHEMA)));

        mapped.history(new OutboxAdminHistoryQuery(
                4,
                10,
                Optional.of(new OutboxAdminHistoryKey(Instant.parse("2026-01-01T00:00:00Z"), UUID.randomUUID()))));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pool).preparedQuery(sql.capture());
        assertEveryTableIsQualified("history", sql.getValue());
        assertTrue(sql.getValue().contains("(recorded_at, operation_id) < ($2, $3)"), sql.getValue());
    }

    @Test
    @DisplayName("summary, detail and content statements never select payload, metadata, scope or carrier columns")
    void projectionsAreNarrow() throws Exception {
        Map<String, String> statements = statements(service(mock(Pool.class), DbSchemas.empty()));

        for (String name : List.of("sqlListPrefix", "sqlFind")) {
            for (String column : NEVER_SELECTED) {
                assertFalse(statements.get(name).contains(column), name + " must not reference " + column);
            }
        }
        String content = statements.get("sqlReadContent");
        for (String column : List.of("metadata", "scope_key", "carrier_id", "claimed_by")) {
            assertFalse(content.contains(column), "content must not reference " + column);
        }
        // the size decision and the text come from one statement, and the value is cut off in the select list
        assertTrue(content.contains("CASE WHEN content_bytes <= $2 THEN payload_text END"), content);
        assertTrue(content.contains("CASE WHEN content_bytes <= $2 THEN headers_text END"), content);
        assertTrue(content.contains("octet_length(payload_text) + COALESCE(octet_length(headers_text), 0)"));
        // each JSONB-to-text conversion is written once, in a materialized CTE, so it runs once per call
        assertTrue(content.contains("AS MATERIALIZED"), content);
        assertEquals(1, content.split("payload::text", -1).length - 1, content);
        assertEquals(1, content.split("headers::text", -1).length - 1, content);
        assertTrue(statements.get("sqlFind").contains("left(last_error, 4096)"), statements.get("sqlFind"));
        List<String> receiptStatements = new ArrayList<>(statements.values());
        for (String receipt : receiptStatements) {
            assertFalse(receipt.contains("scope_key"), receipt);
        }
    }

    @Test
    @DisplayName(
            "the dead-letter cleanup locks candidates with SKIP LOCKED, sorts nothing, and the delete rechecks them")
    void deadLetterCleanupShape() throws Exception {
        PgInboxOutboxRepository repository =
                new PgInboxOutboxRepository(mock(Pool.class), new PgDbExceptionMapper(), DbSchemas.empty());

        Map<String, String> statements = statements(repository);
        String deadLetter = statements.get("sqlCleanupDeadLetter");
        String published = statements.get("sqlCleanupPublished");

        assertNotNull(deadLetter);
        assertTrue(deadLetter.contains(" LIMIT $2 FOR UPDATE SKIP LOCKED)"), deadLetter);
        assertFalse(deadLetter.contains("ORDER BY"), "the backlog is not sorted per batch: " + deadLetter);
        assertTrue(
                deadLetter.endsWith("DELETE FROM outbox WHERE id IN (SELECT id FROM batch)"
                        + " AND state = 'DEAD_LETTER' AND updated_at < NOW() - ($1 * interval '1 day')"),
                deadLetter);
        assertFalse(published.contains("SKIP LOCKED"), "the published cleanup is unchanged: " + published);
    }
}
