// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.await;
import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import dev.vertique.inboxoutbox.query.OutboxEntrySummary;
import dev.vertique.inboxoutbox.query.OutboxQuery;
import dev.vertique.inboxoutbox.query.OutboxQueryConfig;
import io.vertx.core.Vertx;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves with a real schema mapping that every statement of the outbox query and operation services, and the
 * discarded cleanup, addresses the mapped schema.
 *
 * <p>The connection default schema ({@code public}) holds a decoy {@code outbox} table of an incompatible shape,
 * while the migration runs in the mapped schema. Any statement that names a table without the mapping fails
 * against the decoy or silently reads its sentinel row, and both are checked.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PgOutboxQueryOperationsSchemaIT {

    private static final String MAPPED = "messaging";
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("outbox_query_operations_schema");

    static Vertx vertx;
    static OutboxFixture fixture;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();
        fixture = new OutboxFixture(db, vertx, MAPPED);
        fixture.exec("CREATE TABLE public.outbox (decoy_key UUID PRIMARY KEY, sentinel TEXT NOT NULL)");
        fixture.exec("INSERT INTO public.outbox VALUES (gen_random_uuid(), 'decoy-row')");
        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("classpath:db/migration/inbox-outbox")
                .schemas(MAPPED)
                .defaultSchema(MAPPED)
                .load()
                .migrate();
    }

    @AfterAll
    static void tearDown() throws Exception {
        fixture.close();
        await(vertx.close());
    }

    private static DbSchemas mapping() {
        return new DbSchemas(Map.of("inbox-outbox", MAPPED));
    }

    @Test
    @DisplayName("every query, command and cleanup runs in the mapped schema and leaves the decoy untouched")
    void operationsUseMappedSchema() throws Exception {
        RecordingPool recording = new RecordingPool(fixture.pool());
        PgOutboxQueryService query = new PgOutboxQueryService(
                recording.pool(),
                new PgDbExceptionMapper(),
                mapping(),
                OutboxQueryConfig.builder().build());
        PgOutboxOperations operations = new PgOutboxOperations(fixture.pool(), new PgDbExceptionMapper(), mapping());
        PgInboxOutboxRepository repository =
                new PgInboxOutboxRepository(fixture.pool(), new PgDbExceptionMapper(), mapping());
        long toRetry = fixture.seed().insert();
        long toDiscard = fixture.seed().insert();
        long pending = fixture.seed().state("PENDING").insert();
        long expiredDiscard =
                fixture.seed().state("DISCARDED").updated(LONG_AGO).insert();

        // reads
        List<OutboxEntrySummary> listed = await(query.list(
                        OutboxQuery.inStates(
                                OutboxEntryState.DEAD_LETTER, OutboxEntryState.PENDING, OutboxEntryState.DISCARDED),
                        PageCursor.first(50)))
                .items();
        assertEquals(4, listed.size());
        assertTrue(await(query.getById(toRetry)).isPresent());
        assertTrue(await(query.getPayload(toRetry)).isPresent());
        assertEquals(3, recording.statements().size());
        for (RecordingPool.Statement statement : recording.statements()) {
            assertTrue(statement.sql().contains("\"messaging\".\"outbox\""), statement.sql());
        }

        // commands
        await(operations.retry(toRetry, OutboxEntryState.DEAD_LETTER));
        await(operations.discard(toDiscard, OutboxEntryState.DEAD_LETTER));
        await(operations.discard(pending, OutboxEntryState.PENDING));
        assertEquals("PENDING", fixture.stateOf(toRetry));
        assertEquals("DISCARDED", fixture.stateOf(toDiscard));
        assertEquals("DISCARDED", fixture.stateOf(pending));
        assertEquals(
                OutboxEntryState.PENDING,
                await(operations.retry(pending, OutboxEntryState.DISCARDED)).current());

        // the discarded cleanup
        assertEquals(1, await(repository.cleanupDiscarded(7, 100)));
        assertFalse(fixture.outboxExists(expiredDiscard));
        assertTrue(fixture.outboxExists(toDiscard));

        // the decoy default schema is exactly as it was
        assertEquals(1, fixture.longValue("SELECT count(*) FROM public.outbox"));
        assertEquals("decoy-row", fixture.string("SELECT sentinel FROM public.outbox"));
        assertEquals(
                "decoy_key,sentinel",
                fixture.string("SELECT string_agg(column_name, ',' ORDER BY ordinal_position)"
                        + " FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'outbox'"));
    }

    @Test
    @DisplayName("without the mapping the same statements resolve through the connection default schema")
    void unmappedServicesFollowConnectionDefault() {
        PgOutboxQueryService query = new PgOutboxQueryService(
                fixture.pool(),
                new PgDbExceptionMapper(),
                DbSchemas.empty(),
                OutboxQueryConfig.builder().build());
        PgOutboxOperations operations =
                new PgOutboxOperations(fixture.pool(), new PgDbExceptionMapper(), DbSchemas.empty());
        PgInboxOutboxRepository repository =
                new PgInboxOutboxRepository(fixture.pool(), new PgDbExceptionMapper(), DbSchemas.empty());

        // the connection default is the decoy schema, whose shape is incompatible
        assertInstanceOf(
                InboxOutboxPersistenceException.class,
                failureOf(query.list(OutboxQuery.inStates(OutboxEntryState.PENDING), PageCursor.first(10))));
        assertInstanceOf(InboxOutboxPersistenceException.class, failureOf(query.getById(1)));
        assertInstanceOf(InboxOutboxPersistenceException.class, failureOf(query.getPayload(1)));
        assertInstanceOf(
                InboxOutboxPersistenceException.class, failureOf(operations.retry(1, OutboxEntryState.DEAD_LETTER)));
        assertInstanceOf(
                InboxOutboxPersistenceException.class, failureOf(operations.discard(1, OutboxEntryState.PENDING)));
        assertInstanceOf(DataAccessException.class, failureOf(repository.cleanupDiscarded(7, 100)));
    }

    @Test
    @DisplayName("the schema-qualified listing index is the one the mapped table owns")
    void listingIndexesLiveInTheMappedSchema() throws SQLException {
        assertEquals(
                3,
                fixture.longValue(
                        "SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname IN"
                                + " ('idx_outbox_listing_updated', 'idx_outbox_listing_created', 'idx_outbox_discarded')",
                        MAPPED));
        assertEquals(
                0,
                fixture.longValue("SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname LIKE"
                        + " 'idx_outbox%'"));
    }
}
