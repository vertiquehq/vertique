// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static dev.vertique.inboxoutbox.postgresql.DeadLetterFixture.await;
import static dev.vertique.inboxoutbox.postgresql.DeadLetterFixture.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.admin.InboxOutboxAdminConfig;
import dev.vertique.inboxoutbox.admin.OutboxAdminActor;
import dev.vertique.inboxoutbox.admin.OutboxAdminCommand;
import dev.vertique.inboxoutbox.admin.OutboxAdminHistoryQuery;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterQuery;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import io.vertx.core.Vertx;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves with a real schema mapping that every administration statement addresses the mapped schema.
 *
 * <p>The connection default schema ({@code public}) holds decoy {@code outbox} and {@code inbox} tables of an
 * incompatible shape and no receipt table, while the migration runs in the mapped schema. Any statement that names
 * a table without the mapping fails against the decoy or silently reads its sentinel row, and both are checked.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PgOutboxDeadLetterSchemaIT {

    private static final String MAPPED = "messaging";

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("outbox_dead_letter_schema");

    static Vertx vertx;
    static DeadLetterFixture fixture;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();
        fixture = new DeadLetterFixture(db, vertx, MAPPED);
        fixture.exec("CREATE TABLE public.outbox (decoy_key UUID PRIMARY KEY, sentinel TEXT NOT NULL)");
        fixture.exec("CREATE TABLE public.inbox (decoy_key UUID PRIMARY KEY, sentinel TEXT NOT NULL)");
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

    @Test
    @DisplayName("every administration operation runs in the mapped schema and leaves the decoy untouched")
    void operationsUseMappedSchema() throws Exception {
        PgOutboxDeadLetterService mapped = new PgOutboxDeadLetterService(
                fixture.pool(),
                new PgDbExceptionMapper(),
                new DbSchemas(Map.of("inbox-outbox", MAPPED)),
                InboxOutboxAdminConfig.builder().build());
        PgInboxOutboxRepository repository = new PgInboxOutboxRepository(
                fixture.pool(), new PgDbExceptionMapper(), new DbSchemas(Map.of("inbox-outbox", MAPPED)));
        OutboxAdminActor actor = new OutboxAdminActor("user", "alice");
        long retried = fixture.seed().revision(1).insert();
        long discarded = fixture.seed().insert();
        long expired =
                fixture.seed().updated(Instant.parse("2020-01-01T00:00:00Z")).insert();

        // reads
        assertEquals(
                3,
                await(mapped.list(OutboxDeadLetterQuery.builder().build()))
                        .items()
                        .size());
        assertTrue(await(mapped.find(retried)).isPresent());
        assertTrue(await(mapped.readContent(retried)).isPresent());

        // commands, with their receipts in the mapped schema
        await(mapped.retry(new OutboxAdminCommand(UUID.randomUUID(), retried, 1, "reason", actor)));
        await(mapped.discard(new OutboxAdminCommand(UUID.randomUUID(), discarded, 0, "reason", actor)));
        assertEquals("PENDING", fixture.stateOf(retried));
        assertFalse(fixture.outboxExists(discarded));
        assertEquals(2, fixture.receipts());
        assertEquals(
                1,
                await(mapped.history(OutboxAdminHistoryQuery.of(retried)))
                        .items()
                        .size());
        assertEquals(
                1,
                await(mapped.history(OutboxAdminHistoryQuery.of(discarded)))
                        .items()
                        .size());

        // the safe dead-letter cleanup and the receipt retention
        assertEquals(1, await(repository.cleanupDeadLetter(30, 100)));
        assertFalse(fixture.outboxExists(expired));
        fixture.exec("UPDATE outbox_admin_operations SET recorded_at = ?", Instant.parse("2020-01-01T00:00:00Z"));
        assertEquals(2, await(mapped.cleanupOperations(90, 100)));

        // the decoy default schema is exactly as it was
        assertEquals(1, fixture.longValue("SELECT count(*) FROM public.outbox"));
        assertEquals("decoy-row", fixture.string("SELECT sentinel FROM public.outbox"));
        assertEquals(
                0,
                fixture.longValue("SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_schema = 'public' AND table_name = 'outbox_admin_operations'"));
    }

    @Test
    @DisplayName("without the mapping the same statements resolve through the connection default schema")
    void unmappedServiceFollowsConnectionDefault() {
        PgOutboxDeadLetterService unmapped = new PgOutboxDeadLetterService(
                fixture.pool(),
                new PgDbExceptionMapper(),
                DbSchemas.empty(),
                InboxOutboxAdminConfig.builder().build());

        // the connection default is the decoy schema, whose shape is incompatible
        assertInstanceOf(
                InboxOutboxPersistenceException.class,
                failureOf(unmapped.list(OutboxDeadLetterQuery.builder().build())));
        assertInstanceOf(
                InboxOutboxPersistenceException.class, failureOf(unmapped.history(OutboxAdminHistoryQuery.of(1))));
    }
}
