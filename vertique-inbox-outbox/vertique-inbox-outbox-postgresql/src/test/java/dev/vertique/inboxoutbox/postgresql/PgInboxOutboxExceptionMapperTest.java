// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.exception.OptimisticLockingFailureException;
import dev.vertique.db.exception.PessimisticLockingFailureException;
import dev.vertique.db.exception.TransientDataAccessException;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PgInboxOutboxExceptionMapper} — translation policy, retryability, cause
 * preservation, message sanitization, and pass-through for non-DB throwables.
 */
class PgInboxOutboxExceptionMapperTest {

    PgInboxOutboxExceptionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new PgInboxOutboxExceptionMapper();
    }

    // --- Retryable translations ---

    @Nested
    class OptimisticLocking {

        @Test
        @DisplayName("OptimisticLockingFailureException maps to retryable InboxOutboxPersistenceException")
        void optimisticLockingMapsToRetryablePersistenceException() {
            OptimisticLockingFailureException cause = new OptimisticLockingFailureException("conflict", null, "40001");

            Throwable result = mapper.translate(cause, "outbox publish");

            assertInstanceOf(InboxOutboxPersistenceException.class, result);
            InboxOutboxPersistenceException ex = (InboxOutboxPersistenceException) result;
            assertTrue(ex.retryable(), "optimistic locking failure must be retryable");
            assertSame(cause, ex.getCause(), "original cause must be preserved");
        }
    }

    @Nested
    class PessimisticLocking {

        @Test
        @DisplayName("PessimisticLockingFailureException maps to retryable InboxOutboxPersistenceException")
        void pessimisticLockingMapsToRetryablePersistenceException() {
            PessimisticLockingFailureException cause =
                    new PessimisticLockingFailureException("lock timeout", null, "55P03");

            Throwable result = mapper.translate(cause, "inbox tryInsert");

            assertInstanceOf(InboxOutboxPersistenceException.class, result);
            InboxOutboxPersistenceException ex = (InboxOutboxPersistenceException) result;
            assertTrue(ex.retryable(), "pessimistic locking failure must be retryable");
            assertSame(cause, ex.getCause(), "original cause must be preserved");
        }
    }

    @Nested
    class TransientDataAccess {

        @Test
        @DisplayName("TransientDataAccessException maps to retryable InboxOutboxPersistenceException")
        void transientDataAccessMapsToRetryablePersistenceException() {
            TransientDataAccessException cause = new TransientDataAccessException("deadlock", null, "40P01");

            Throwable result = mapper.translate(cause, "outbox publish");

            assertInstanceOf(InboxOutboxPersistenceException.class, result);
            InboxOutboxPersistenceException ex = (InboxOutboxPersistenceException) result;
            assertTrue(ex.retryable(), "transient data access failure must be retryable");
            assertSame(cause, ex.getCause(), "original cause must be preserved");
        }
    }

    @Nested
    class GenericDataAccess {

        @Test
        @DisplayName("generic DataAccessException maps to non-retryable InboxOutboxPersistenceException")
        void genericDataAccessMapsToNonRetryablePersistenceException() {
            DataAccessException cause = new DataAccessException("query failed", null, null, null, null);

            Throwable result = mapper.translate(cause, "outbox publish");

            assertInstanceOf(InboxOutboxPersistenceException.class, result);
            InboxOutboxPersistenceException ex = (InboxOutboxPersistenceException) result;
            assertFalse(ex.retryable(), "generic data access failure must not be retryable");
            assertSame(cause, ex.getCause(), "original cause must be preserved");
        }
    }

    // --- Pass-through (non-DB throwables) ---

    @Nested
    class PassThrough {

        @Test
        @DisplayName("IllegalStateException passes through unchanged (same instance)")
        void illegalStateExceptionPassesThroughUnchanged() {
            IllegalStateException original = new IllegalStateException("unexpected");

            Throwable result = mapper.translate(original, "outbox publish");

            assertSame(original, result, "non-DataAccessException must pass through unchanged");
        }

        @Test
        @DisplayName("ClaimScopeException passes through unchanged — not a DataAccessException")
        void claimScopeExceptionPassesThroughUnchanged() {
            // ClaimScopeException extends InboxOutboxConfigurationException, not DataAccessException;
            // it must never be wrapped by the exception mapper.
            ClaimScopeException original =
                    new ClaimScopeException("claim scope for destination type 'SERVICE' produced a null target set");

            Throwable result = mapper.translate(original, "outbox publish");

            assertSame(original, result, "ClaimScopeException must pass through unchanged");
        }
    }

    // --- Message sanitization ---

    @Nested
    class MessageSanitization {

        @Test
        @DisplayName("translated message excludes raw DB message but includes cause simple class name")
        void translatedMessageExcludesRawDbMessageButIncludesCauseClassName() {
            DataAccessException cause = new DataAccessException(
                    "SELECT * FROM sensitive_table WHERE secret = 'value'", null, null, null, null);

            Throwable result = mapper.translate(cause, "outbox publish");

            assertInstanceOf(InboxOutboxPersistenceException.class, result);
            String message = result.getMessage();
            assertNotNull(message);
            // Message must contain the cause's simple class name
            assertTrue(
                    message.contains("DataAccessException"),
                    "message must include cause simple class name but was: " + message);
            // Message must NOT contain the raw DB message text (sanitization invariant)
            assertFalse(
                    message.contains("sensitive_table"),
                    "message must not forward raw DB message text but was: " + message);
            assertFalse(message.contains("secret"), "message must not forward raw DB message text but was: " + message);
        }

        @Test
        @DisplayName("translated message includes operation name")
        void translatedMessageIncludesOperationName() {
            DataAccessException cause = new DataAccessException("raw detail", null, null, null, null);

            Throwable result = mapper.translate(cause, "inbox tryInsert");

            assertInstanceOf(InboxOutboxPersistenceException.class, result);
            String message = result.getMessage();
            assertTrue(message.contains("inbox tryInsert"), "message must include operation name but was: " + message);
        }
    }
}
