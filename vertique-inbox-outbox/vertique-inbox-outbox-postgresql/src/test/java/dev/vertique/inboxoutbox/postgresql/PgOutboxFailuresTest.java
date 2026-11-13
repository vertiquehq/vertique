// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.exception.ConflictException;
import dev.vertique.core.exception.NotFoundException;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.exception.PessimisticLockingFailureException;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import dev.vertique.inboxoutbox.ops.OutboxEntryNotFoundException;
import dev.vertique.inboxoutbox.ops.OutboxEntryStateException;
import dev.vertique.inboxoutbox.query.OutboxPayloadTooLargeException;
import io.vertx.pgclient.PgException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the failure translation shared by the outbox query and operation services. */
@DisplayName("PgOutboxFailures")
class PgOutboxFailuresTest {

    private static final PgDbExceptionMapper MAPPER = new PgDbExceptionMapper();

    @Test
    @DisplayName("semantic exceptions and persistence exceptions pass through as the same instance")
    void semanticExceptionsPassThrough() {
        Throwable[] passThrough = {
            new ValidationException("bad"),
            new OutboxEntryNotFoundException(3),
            new OutboxEntryStateException(3, OutboxEntryState.DEAD_LETTER, OutboxEntryState.PENDING),
            new OutboxPayloadTooLargeException(),
            new NotFoundException("missing"),
            new ConflictException("conflict"),
            new InboxOutboxPersistenceException("already translated", null, true)
        };

        for (Throwable failure : passThrough) {
            assertSame(
                    failure,
                    PgOutboxFailures.translate(MAPPER, failure, "op"),
                    failure.getClass().getSimpleName());
        }
    }

    @Test
    @DisplayName("a lock failure becomes a retryable persistence exception that names the type and SQL state only")
    void lockFailureIsRetryable() {
        PgException driver = new PgException(
                "canceling statement due to lock timeout: SELECT secret FROM outbox", "ERROR", "55P03", null);

        Throwable translated = PgOutboxFailures.translate(MAPPER, driver, "outbox entry retry");

        InboxOutboxPersistenceException persistence =
                assertInstanceOf(InboxOutboxPersistenceException.class, translated);
        assertTrue(persistence.retryable());
        assertEquals(
                "Lock acquisition failure during inbox/outbox operation 'outbox entry retry'"
                        + " [PessimisticLockingFailureException, sqlState=55P03]",
                persistence.getMessage());
        assertInstanceOf(PessimisticLockingFailureException.class, persistence.getCause());
    }

    @Test
    @DisplayName("a driver failure of another kind becomes a non-retryable persistence exception without driver text")
    void otherDriverFailureIsNotRetryable() {
        PgException driver = new PgException("relation \"nope\".\"outbox\" does not exist", "ERROR", "42P01", null);

        Throwable translated = PgOutboxFailures.translate(MAPPER, driver, "outbox entry list");

        InboxOutboxPersistenceException persistence =
                assertInstanceOf(InboxOutboxPersistenceException.class, translated);
        assertFalse(persistence.retryable());
        assertFalse(persistence.getMessage().contains("nope"), persistence.getMessage());
        assertFalse(persistence.getMessage().contains("does not exist"), persistence.getMessage());
        assertTrue(persistence.getMessage().contains("'outbox entry list'"), persistence.getMessage());
        assertTrue(persistence.getMessage().contains("sqlState=42P01"), persistence.getMessage());
        assertInstanceOf(DataAccessException.class, persistence.getCause());
    }

    @Test
    @DisplayName("an unexpected exception becomes a non-retryable persistence exception that does not echo its message")
    void unexpectedExceptionIsWrapped() {
        RuntimeException failure = new RuntimeException("SELECT payload FROM outbox WHERE secret = 'x'");

        Throwable translated = PgOutboxFailures.translate(MAPPER, failure, "outbox entry get");

        InboxOutboxPersistenceException persistence =
                assertInstanceOf(InboxOutboxPersistenceException.class, translated);
        assertFalse(persistence.retryable());
        assertFalse(persistence.getMessage().contains("SELECT"), persistence.getMessage());
        assertFalse(persistence.getMessage().contains("secret"), persistence.getMessage());
        assertTrue(persistence.getMessage().contains("'outbox entry get'"), persistence.getMessage());
        assertInstanceOf(DataAccessException.class, persistence.getCause());
        assertSame(failure, persistence.getCause().getCause());
    }

    @Test
    @DisplayName("a failure the database mapper leaves untranslated still becomes an opaque persistence exception")
    void untranslatedFailureIsWrapped() {
        PgDbExceptionMapper passive = mock(PgDbExceptionMapper.class);
        IllegalStateException failure = new IllegalStateException("SELECT secret FROM outbox");
        when(passive.translate(failure, "outbox entry list")).thenReturn(failure);

        Throwable translated = PgOutboxFailures.translate(passive, failure, "outbox entry list");

        InboxOutboxPersistenceException persistence =
                assertInstanceOf(InboxOutboxPersistenceException.class, translated);
        assertFalse(persistence.retryable());
        assertEquals(
                "Persistence failure during inbox/outbox operation 'outbox entry list' [IllegalStateException]",
                persistence.getMessage());
        assertSame(failure, persistence.getCause());
    }
}
