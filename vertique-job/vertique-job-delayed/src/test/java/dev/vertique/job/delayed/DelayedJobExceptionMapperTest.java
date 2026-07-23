// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.exception.OptimisticLockingFailureException;
import dev.vertique.db.exception.PessimisticLockingFailureException;
import dev.vertique.db.exception.TransientDataAccessException;
import dev.vertique.job.delayed.exception.DelayedJobPersistenceException;
import dev.vertique.job.delayed.exception.DelayedJobRegistrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DelayedJobExceptionMapper}: exception-type translation, retryable classification,
 * cause preservation, message sanitization, and pass-through of non-DB throwables.
 */
@DisplayName("DelayedJobExceptionMapper")
class DelayedJobExceptionMapperTest {

    DelayedJobExceptionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new DelayedJobExceptionMapper();
    }

    // --- Optimistic locking ---

    @Nested
    @DisplayName("OptimisticLockingFailureException")
    class OptimisticLocking {

        @Test
        @DisplayName("maps to DelayedJobPersistenceException with retryable=true")
        void mapsToRetryablePersistenceException() {
            OptimisticLockingFailureException cause = new OptimisticLockingFailureException("conflict", null);

            Throwable result = mapper.translate(cause, "enqueue");

            DelayedJobPersistenceException ex = assertInstanceOf(DelayedJobPersistenceException.class, result);
            assertTrue(ex.retryable(), "OptimisticLockingFailureException must be retryable");
            assertSame(cause, ex.getCause(), "Cause must be the original exception");
        }

        @Test
        @DisplayName("translated message does not contain raw DB message text")
        void messageSanitized() {
            OptimisticLockingFailureException cause =
                    new OptimisticLockingFailureException("raw sensitive db detail", null);

            Throwable result = mapper.translate(cause, "enqueue");

            assertFalse(
                    result.getMessage().contains("raw sensitive db detail"),
                    "Translated message must not contain the raw DB message");
        }

        @Test
        @DisplayName("translated message contains the cause simple class name")
        void messageContainsCauseSimpleClassName() {
            OptimisticLockingFailureException cause = new OptimisticLockingFailureException("detail", null);

            Throwable result = mapper.translate(cause, "enqueue");

            assertTrue(
                    result.getMessage().contains("OptimisticLockingFailureException"),
                    "Translated message must contain the cause simple class name");
        }
    }

    // --- Pessimistic locking ---

    @Nested
    @DisplayName("PessimisticLockingFailureException")
    class PessimisticLocking {

        @Test
        @DisplayName("maps to DelayedJobPersistenceException with retryable=true")
        void mapsToRetryablePersistenceException() {
            PessimisticLockingFailureException cause = new PessimisticLockingFailureException("lock timeout", null);

            Throwable result = mapper.translate(cause, "enqueue");

            DelayedJobPersistenceException ex = assertInstanceOf(DelayedJobPersistenceException.class, result);
            assertTrue(ex.retryable(), "PessimisticLockingFailureException must be retryable");
            assertSame(cause, ex.getCause(), "Cause must be the original exception");
        }
    }

    // --- Transient data access ---

    @Nested
    @DisplayName("TransientDataAccessException")
    class TransientDataAccess {

        @Test
        @DisplayName("maps to DelayedJobPersistenceException with retryable=true")
        void mapsToRetryablePersistenceException() {
            TransientDataAccessException cause = new TransientDataAccessException("deadlock", null);

            Throwable result = mapper.translate(cause, "enqueue");

            DelayedJobPersistenceException ex = assertInstanceOf(DelayedJobPersistenceException.class, result);
            assertTrue(ex.retryable(), "TransientDataAccessException must be retryable");
            assertSame(cause, ex.getCause(), "Cause must be the original exception");
        }
    }

    // --- Generic DataAccessException ---

    @Nested
    @DisplayName("generic DataAccessException")
    class GenericDataAccess {

        @Test
        @DisplayName("maps to DelayedJobPersistenceException with retryable=false")
        void mapsToNonRetryablePersistenceException() {
            DataAccessException cause = new DataAccessException("generic db error", null);

            Throwable result = mapper.translate(cause, "enqueue");

            DelayedJobPersistenceException ex = assertInstanceOf(DelayedJobPersistenceException.class, result);
            assertFalse(ex.retryable(), "Generic DataAccessException must not be retryable");
            assertSame(cause, ex.getCause(), "Cause must be the original exception");
        }

        @Test
        @DisplayName("translated message contains sqlState when present")
        void messageContainsSqlStateWhenPresent() {
            DataAccessException cause = new DataAccessException("detail", null, "40001");

            Throwable result = mapper.translate(cause, "enqueue");

            assertTrue(
                    result.getMessage().contains("sqlState=40001"),
                    "Translated message must include sqlState when present");
        }
    }

    // --- Non-DB throwable pass-through ---

    @Nested
    @DisplayName("non-DB throwable pass-through")
    class NonDbPassThrough {

        @Test
        @DisplayName("IllegalArgumentException passes through unchanged")
        void illegalArgumentExceptionPassesThrough() {
            IllegalArgumentException original = new IllegalArgumentException("bad input");

            Throwable result = mapper.translate(original, "enqueue");

            assertSame(original, result, "Non-DB throwable must be returned as the same instance");
        }

        @Test
        @DisplayName("DelayedJobRegistrationException is a ConfigurationException")
        void delayedJobRegistrationExceptionIsConfigurationException() {
            DelayedJobRegistrationException ex = new DelayedJobRegistrationException(java.util.List.of("v"));
            assertInstanceOf(ConfigurationException.class, ex);
        }

        @Test
        @DisplayName("already-semantic exception passes through unchanged")
        void semanticExceptionPassesThrough() {
            DelayedJobPersistenceException original =
                    new DelayedJobPersistenceException("already mapped", new RuntimeException(), false);

            Throwable result = mapper.translate(original, "enqueue");

            assertSame(original, result, "Already-semantic exception must not be double-wrapped");
        }
    }
}
