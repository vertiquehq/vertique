// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.query.PageCursor;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.query.OutboxQuery;
import dev.vertique.inboxoutbox.query.OutboxQueryConfig;
import io.vertx.sqlclient.Pool;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Construction rules and database-free validation of {@link PgOutboxQueryService} and {@link PgOutboxOperations}. */
@DisplayName("outbox query and operation services: construction")
class PgOutboxServicesConstructionTest {

    private static final PgDbExceptionMapper MAPPER = new PgDbExceptionMapper();
    private static final DbSchemas BAD_SCHEMA = new DbSchemas(Map.of("inbox-outbox", "bad.schema"));

    @Test
    @DisplayName("the query service requires a pool, a mapper and a config")
    void queryServiceRequiresCollaborators() {
        Pool pool = mock(Pool.class);
        OutboxQueryConfig config = OutboxQueryConfig.builder().build();

        assertEquals(
                "pool",
                assertThrows(
                                NullPointerException.class,
                                () -> new PgOutboxQueryService(null, MAPPER, DbSchemas.empty(), config))
                        .getMessage());
        assertEquals(
                "exceptionMapper",
                assertThrows(
                                NullPointerException.class,
                                () -> new PgOutboxQueryService(pool, null, DbSchemas.empty(), config))
                        .getMessage());
        assertEquals(
                "config",
                assertThrows(
                                NullPointerException.class,
                                () -> new PgOutboxQueryService(pool, MAPPER, DbSchemas.empty(), null))
                        .getMessage());
    }

    @Test
    @DisplayName("the operations service requires a pool, a mapper and a lock timeout of at least one millisecond")
    void operationsServiceRequiresCollaborators() {
        Pool pool = mock(Pool.class);

        assertEquals(
                "pool",
                assertThrows(NullPointerException.class, () -> new PgOutboxOperations(null, MAPPER, DbSchemas.empty()))
                        .getMessage());
        assertEquals(
                "exceptionMapper",
                assertThrows(NullPointerException.class, () -> new PgOutboxOperations(pool, null, DbSchemas.empty()))
                        .getMessage());
        assertEquals(
                "lockTimeout",
                assertThrows(
                                NullPointerException.class,
                                () -> new PgOutboxOperations(pool, MAPPER, DbSchemas.empty(), null))
                        .getMessage());
        for (Duration tooShort : new Duration[] {Duration.ZERO, Duration.ofNanos(999_999), Duration.ofMillis(-5)}) {
            assertEquals(
                    "lockTimeout must be at least one millisecond",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> new PgOutboxOperations(pool, MAPPER, DbSchemas.empty(), tooShort))
                            .getMessage(),
                    tooShort.toString());
        }
        new PgOutboxOperations(pool, MAPPER, DbSchemas.empty(), Duration.ofMillis(1));
    }

    @Test
    @DisplayName("a schema name that is not a single identifier is rejected by both services")
    void invalidSchemaIsRejected() {
        Pool pool = mock(Pool.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> new PgOutboxQueryService(
                        pool, MAPPER, BAD_SCHEMA, OutboxQueryConfig.builder().build()));
        assertThrows(IllegalArgumentException.class, () -> new PgOutboxOperations(pool, MAPPER, BAD_SCHEMA));
    }

    @Test
    @DisplayName("invalid arguments fail before any statement is sent to the database")
    void validationNeverReachesThePool() {
        Pool pool = mock(Pool.class);
        PgOutboxQueryService query = new PgOutboxQueryService(
                pool, MAPPER, DbSchemas.empty(), OutboxQueryConfig.builder().build());
        PgOutboxOperations operations = new PgOutboxOperations(pool, MAPPER, DbSchemas.empty());
        OutboxQuery pending = OutboxQuery.inStates(OutboxEntryState.PENDING);

        assertEquals(
                ValidationException.class,
                query.list(null, PageCursor.first(10)).cause().getClass());
        assertEquals(
                ValidationException.class, query.list(pending, null).cause().getClass());
        assertEquals(ValidationException.class, query.getById(0).cause().getClass());
        assertEquals(ValidationException.class, query.getPayload(-1).cause().getClass());
        assertEquals(
                ValidationException.class,
                operations.retry(0, OutboxEntryState.DEAD_LETTER).cause().getClass());
        assertEquals(
                ValidationException.class, operations.retry(1, null).cause().getClass());
        assertEquals(
                ValidationException.class,
                operations.retry(1, OutboxEntryState.PENDING).cause().getClass());
        assertEquals(
                ValidationException.class,
                operations.discard(1, OutboxEntryState.PROCESSING).cause().getClass());
        assertEquals(
                ValidationException.class,
                operations.discard(-1, OutboxEntryState.PENDING).cause().getClass());
        verifyNoInteractions(pool);
    }
}
