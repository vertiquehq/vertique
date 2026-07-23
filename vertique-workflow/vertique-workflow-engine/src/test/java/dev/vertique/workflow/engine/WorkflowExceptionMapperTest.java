// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConflictException;
import dev.vertique.core.exception.NotFoundException;
import dev.vertique.core.exception.TechnicalException;
import dev.vertique.db.exception.ConnectionException;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.exception.DeadlockException;
import dev.vertique.db.exception.OptimisticLockingFailureException;
import dev.vertique.db.exception.PessimisticLockingFailureException;
import dev.vertique.db.exception.QueryTimeoutException;
import dev.vertique.db.exception.TransientDataAccessException;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import dev.vertique.workflow.exception.WorkflowSubjectVersionUnavailableException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkflowExceptionMapper} (stage-2, workflow boundary).
 *
 * <p>Exercises the full retryability-classification contract of {@link
 * WorkflowExceptionMapper#translate(Throwable, String)} without a database. The key invariant under
 * test is that <em>every</em> {@link TransientDataAccessException} — including {@link
 * DeadlockException}, {@link QueryTimeoutException}, {@link ConnectionException}, and the raw base
 * type produced for SQL-state classes 53/58 — maps to a <b>retryable</b> {@link
 * WorkflowPersistenceException}; {@link PessimisticLockingFailureException} (which is <em>not</em>
 * transient) is also retryable; a generic non-transient {@link DataAccessException} is
 * non-retryable; {@link OptimisticLockingFailureException} maps to {@link WorkflowConflictException};
 * and workflow / unknown throwables pass through unchanged.
 *
 * <p>After the exception-hierarchy reparenting, {@link WorkflowException} is exclusively the
 * business-rule root (caught by the explicit guard), while other workflow semantic exceptions
 * ({@link WorkflowConflictException}, {@link WorkflowInstanceNotFoundException}, {@link
 * WorkflowPersistenceException}, etc.) extend core exception roots and pass through the final
 * {@code return failure;} branch because they are not {@link DataAccessException} subtypes.
 *
 * <p>The message-sanitization invariant is also verified: the translated message includes the
 * cause's simple class name and SQL state but never the raw {@link Throwable#getMessage()} text.
 */
class WorkflowExceptionMapperTest {

    private static final String SECRET_MESSAGE = "SENSITIVE-sql-fragment-do-not-leak";

    private final WorkflowExceptionMapper mapper = new WorkflowExceptionMapper();

    // --- Retryable transient failures ---

    @Nested
    @DisplayName("transient DataAccessException subtypes map to retryable WorkflowPersistenceException")
    class TransientFailures {

        @Test
        @DisplayName("DeadlockException -> retryable, cause preserved")
        void deadlockIsRetryable() {
            DeadlockException cause = new DeadlockException("deadlock", new RuntimeException(), "40P01");

            Throwable result = mapper.translate(cause, "op");

            WorkflowPersistenceException mapped = assertInstanceOf(WorkflowPersistenceException.class, result);
            assertTrue(mapped.retryable(), "deadlock must be retryable");
            assertSame(cause, mapped.getCause(), "cause must be preserved");
        }

        @Test
        @DisplayName("QueryTimeoutException -> retryable, cause preserved")
        void queryTimeoutIsRetryable() {
            QueryTimeoutException cause = new QueryTimeoutException("timeout", new RuntimeException(), "57014");

            Throwable result = mapper.translate(cause, "op");

            WorkflowPersistenceException mapped = assertInstanceOf(WorkflowPersistenceException.class, result);
            assertTrue(mapped.retryable(), "query timeout must be retryable");
            assertSame(cause, mapped.getCause(), "cause must be preserved");
        }

        @Test
        @DisplayName("ConnectionException -> retryable, cause preserved")
        void connectionFailureIsRetryable() {
            ConnectionException cause = new ConnectionException("conn lost", new RuntimeException(), "08006");

            Throwable result = mapper.translate(cause, "op");

            WorkflowPersistenceException mapped = assertInstanceOf(WorkflowPersistenceException.class, result);
            assertTrue(mapped.retryable(), "connection failure must be retryable");
            assertSame(cause, mapped.getCause(), "cause must be preserved");
        }

        @Test
        @DisplayName("raw TransientDataAccessException (class 53/58) -> retryable, cause preserved")
        void rawTransientIsRetryable() {
            TransientDataAccessException cause =
                    new TransientDataAccessException("insufficient resources", new RuntimeException(), "53100");

            Throwable result = mapper.translate(cause, "op");

            WorkflowPersistenceException mapped = assertInstanceOf(WorkflowPersistenceException.class, result);
            assertTrue(mapped.retryable(), "raw transient failure must be retryable");
            assertSame(cause, mapped.getCause(), "cause must be preserved");
        }

        @Test
        @DisplayName("PessimisticLockingFailureException (not transient) -> retryable, cause preserved")
        void pessimisticLockIsRetryable() {
            PessimisticLockingFailureException cause =
                    new PessimisticLockingFailureException("lock not available", new RuntimeException(), "55P03");

            Throwable result = mapper.translate(cause, "op");

            WorkflowPersistenceException mapped = assertInstanceOf(WorkflowPersistenceException.class, result);
            assertTrue(mapped.retryable(), "pessimistic lock failure must be retryable");
            assertSame(cause, mapped.getCause(), "cause must be preserved");
        }
    }

    // --- Non-retryable / conflict / passthrough ---

    @Nested
    @DisplayName("non-transient failures, conflicts, and passthroughs")
    class OtherClassifications {

        @Test
        @DisplayName("generic DataAccessException -> non-retryable, cause preserved")
        void genericDataAccessIsNonRetryable() {
            DataAccessException cause = new DataAccessException("generic failure", new RuntimeException(), "XX000");

            Throwable result = mapper.translate(cause, "op");

            WorkflowPersistenceException mapped = assertInstanceOf(WorkflowPersistenceException.class, result);
            assertFalse(mapped.retryable(), "generic persistence failure must NOT be retryable");
            assertSame(cause, mapped.getCause(), "cause must be preserved");
            assertInstanceOf(TechnicalException.class, result);
        }

        @Test
        @DisplayName("OptimisticLockingFailureException -> WorkflowConflictException, cause preserved")
        void optimisticLockMapsToConflict() {
            OptimisticLockingFailureException cause =
                    new OptimisticLockingFailureException("serialization failure", new RuntimeException(), "40001");

            Throwable result = mapper.translate(cause, "op");

            WorkflowConflictException mapped = assertInstanceOf(WorkflowConflictException.class, result);
            assertSame(cause, mapped.getCause(), "cause must be preserved");
            assertInstanceOf(ConflictException.class, result);
        }

        @Test
        @DisplayName("business-rule WorkflowException passes through unchanged (guard)")
        void businessRuleWorkflowExceptionPassesThrough() {
            WorkflowSubjectVersionUnavailableException input =
                    new WorkflowSubjectVersionUnavailableException("missing versioned subject");

            Throwable result = mapper.translate(input, "op");

            assertSame(
                    input,
                    result,
                    "business-rule WorkflowException must pass through unchanged via the explicit guard");
        }

        @Test
        @DisplayName("source-thrown WorkflowConflictException passes through unchanged and is a core ConflictException")
        void sourceThrownConflictPassesThrough() {
            WorkflowConflictException input = new WorkflowConflictException("conflict");

            Throwable result = mapper.translate(input, "op");

            assertSame(input, result);
            assertInstanceOf(ConflictException.class, result);
        }

        @Test
        @DisplayName("source-thrown not-found passes through unchanged and is a core NotFoundException")
        void sourceThrownNotFoundPassesThrough() {
            WorkflowInstanceNotFoundException input =
                    new WorkflowInstanceNotFoundException(new WorkflowInstanceId(UUID.randomUUID()));

            Throwable result = mapper.translate(input, "op");

            assertSame(input, result);
            assertInstanceOf(NotFoundException.class, result);
        }

        @Test
        @DisplayName("WorkflowPersistenceException is not re-wrapped (passes through)")
        void workflowPersistenceExceptionNotReWrapped() {
            WorkflowPersistenceException input =
                    new WorkflowPersistenceException("p", new IllegalStateException("x"), false);

            assertSame(input, mapper.translate(input, "op"));
        }

        @Test
        @DisplayName("unknown RuntimeException passes through unchanged (same instance)")
        void unknownRuntimeExceptionPassesThrough() {
            IllegalStateException input = new IllegalStateException("bug");

            Throwable result = mapper.translate(input, "op");

            assertSame(input, result, "unknown non-DB throwable must pass through unchanged");
        }
    }

    // --- Message sanitization ---

    @Test
    @DisplayName("translated message contains cause class name + sqlState but not the raw cause message")
    void messageIsSanitized() {
        DeadlockException cause = new DeadlockException(SECRET_MESSAGE, new RuntimeException(), "40P01");

        Throwable result = mapper.translate(cause, "claimNextBranch");
        String message = result.getMessage();

        assertTrue(message.contains("DeadlockException"), "message must contain the cause's simple class name");
        assertTrue(message.contains("40P01"), "message must contain the SQL state");
        assertTrue(message.contains("claimNextBranch"), "message must contain the operation label");
        assertFalse(message.contains(SECRET_MESSAGE), "message must NOT contain the raw cause message text");
    }
}
