// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.exception.ConnectionException;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.exception.DataIntegrityViolationException;
import dev.vertique.db.exception.DeadlockException;
import dev.vertique.db.exception.ForeignKeyViolationException;
import dev.vertique.db.exception.InvalidDataAccessUsageException;
import dev.vertique.db.exception.OptimisticLockingFailureException;
import dev.vertique.db.exception.PessimisticLockingFailureException;
import dev.vertique.db.exception.QueryTimeoutException;
import dev.vertique.db.exception.TransientDataAccessException;
import dev.vertique.db.exception.UniqueConstraintViolationException;
import io.vertx.core.VertxException;
import io.vertx.pgclient.PgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link PgDbExceptionMapper}: SQL-state-to-exception mapping for exact codes and
 * class-level prefixes, VertxException timeout detection, DataAccessException pass-through,
 * the generic catch-all fallback, and preservation of SQL state codes on the produced exception.
 */
class PgDbExceptionMapperTest {

    private PgDbExceptionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new PgDbExceptionMapper();
    }

    // --- Helpers ---

    /**
     * Creates a {@link PgException} using the 4-arg constructor:
     * {@code (errorMessage, severity, sqlState, detail)}.
     */
    private static PgException pgException(String sqlState, String message) {
        return new PgException(message, "ERROR", sqlState, null);
    }

    // --- SQL state mapping ---

    @Test
    @DisplayName("23505 (unique violation) → UniqueConstraintViolationException with SQL state preserved")
    void shouldMapUniqueViolation() {
        PgException ex = pgException("23505", "duplicate key value violates unique constraint");
        UniqueConstraintViolationException result =
                assertInstanceOf(UniqueConstraintViolationException.class, mapper.translate(ex, "insert failed"));
        assertEquals("insert failed", result.getMessage());
        assertEquals("23505", result.sqlState());
    }

    @Test
    @DisplayName("23503 (foreign key violation) → ForeignKeyViolationException with SQL state preserved")
    void shouldMapForeignKeyViolation() {
        PgException ex = pgException("23503", "insert or update on table violates foreign key constraint");
        ForeignKeyViolationException result =
                assertInstanceOf(ForeignKeyViolationException.class, mapper.translate(ex, "insert failed"));
        assertEquals("23503", result.sqlState());
    }

    @Test
    @DisplayName("40001 (serialization failure) → OptimisticLockingFailureException with SQL state preserved")
    void shouldMapSerializationFailure() {
        PgException ex = pgException("40001", "could not serialize access due to concurrent update");
        OptimisticLockingFailureException result =
                assertInstanceOf(OptimisticLockingFailureException.class, mapper.translate(ex, "tx failed"));
        assertEquals("40001", result.sqlState());
    }

    @Test
    @DisplayName("40P01 (deadlock detected) → DeadlockException with SQL state preserved")
    void shouldMapDeadlock() {
        PgException ex = pgException("40P01", "deadlock detected");
        DeadlockException result = assertInstanceOf(DeadlockException.class, mapper.translate(ex, "tx failed"));
        assertEquals("40P01", result.sqlState());
    }

    @Test
    @DisplayName("08001 (connection refused) → ConnectionException with SQL state preserved")
    void shouldMapConnectionError() {
        PgException ex = pgException("08001", "connection refused");
        ConnectionException result =
                assertInstanceOf(ConnectionException.class, mapper.translate(ex, "connect failed"));
        assertEquals("08001", result.sqlState());
    }

    @Test
    @DisplayName("08xxx class errors (e.g. 08006 connection lost) → ConnectionException")
    void shouldMapConnectionClassErrors() {
        PgException ex = pgException("08006", "connection lost during query");
        ConnectionException result = assertInstanceOf(ConnectionException.class, mapper.translate(ex, "lost"));
        assertEquals("08006", result.sqlState());
    }

    @Test
    @DisplayName("unknown SQL state (99999) → generic DataAccessException (not a subtype), SQL state preserved")
    void shouldMapUnknownSqlState() {
        PgException ex = pgException("99999", "some unknown error");
        DataAccessException result = assertInstanceOf(DataAccessException.class, mapper.translate(ex, "unknown"));
        assertFalse(
                result instanceof UniqueConstraintViolationException,
                "should not be a UniqueConstraintViolationException");
        assertEquals("99999", result.sqlState());
    }

    @Test
    @DisplayName("null SQL state → DataAccessException with null sqlState()")
    void shouldMapNullSqlState() {
        PgException ex = pgException(null, "no sql state");
        DataAccessException result = assertInstanceOf(DataAccessException.class, mapper.translate(ex, "no state"));
        assertNull(result.sqlState());
    }

    @Test
    @DisplayName("55P03 (lock not available) → PessimisticLockingFailureException with SQL state preserved")
    void shouldMapLockNotAvailable() {
        PgException ex = pgException("55P03", "could not obtain lock on row");
        PessimisticLockingFailureException result =
                assertInstanceOf(PessimisticLockingFailureException.class, mapper.translate(ex, "lock timeout"));
        assertEquals("55P03", result.sqlState());
    }

    @Test
    @DisplayName("57014 (statement timeout) → QueryTimeoutException")
    void shouldMapStatementTimeout() {
        PgException ex = pgException("57014", "canceling statement due to statement timeout");
        assertInstanceOf(QueryTimeoutException.class, mapper.translate(ex, "statement timeout"));
    }

    // --- VertxException mapping ---

    @Test
    @DisplayName("VertxException with 'Timeout' in message → QueryTimeoutException")
    void shouldMapTimeoutVertxException() {
        VertxException ex = new VertxException("Timeout", true);
        assertInstanceOf(QueryTimeoutException.class, mapper.translate(ex, "query timeout"));
    }

    @Test
    @DisplayName(
            "VertxException without 'timeout' in message → generic DataAccessException (not QueryTimeoutException)")
    void shouldMapNonTimeoutVertxException() {
        VertxException ex = new VertxException("Connection reset", true);
        DataAccessException result = assertInstanceOf(DataAccessException.class, mapper.translate(ex, "error"));
        assertFalse(result instanceof QueryTimeoutException, "should not be a QueryTimeoutException");
    }

    // --- Integrity constraint violations (Class 23 expansion) ---

    @Nested
    @DisplayName("Class 23 — integrity constraint violations")
    class IntegrityConstraintExpansion {

        @Test
        @DisplayName("23502 (NOT NULL violation) → DataIntegrityViolationException")
        void shouldMapNotNullViolation() {
            PgException ex = pgException("23502", "null value in column \"name\" violates not-null constraint");
            DataIntegrityViolationException result =
                    assertInstanceOf(DataIntegrityViolationException.class, mapper.translate(ex, "insert failed"));
            assertEquals("23502", result.sqlState());
        }

        @Test
        @DisplayName("23514 (CHECK constraint violation) → DataIntegrityViolationException")
        void shouldMapCheckConstraintViolation() {
            PgException ex = pgException("23514", "new row violates check constraint \"positive_amount\"");
            DataIntegrityViolationException result =
                    assertInstanceOf(DataIntegrityViolationException.class, mapper.translate(ex, "insert failed"));
            assertEquals("23514", result.sqlState());
        }

        @Test
        @DisplayName("23P01 (exclusion constraint violation) → DataIntegrityViolationException")
        void shouldMapExclusionViolation() {
            PgException ex = pgException("23P01", "conflicting key value violates exclusion constraint");
            DataIntegrityViolationException result =
                    assertInstanceOf(DataIntegrityViolationException.class, mapper.translate(ex, "insert failed"));
            assertEquals("23P01", result.sqlState());
        }
    }

    // --- Class-level SQL state mappings ---

    @Nested
    @DisplayName("Class-level SQL state fallbacks")
    class ClassLevelMappings {

        @Test
        @DisplayName("22xxx (data exception) → InvalidDataAccessUsageException")
        void shouldMapDataException() {
            PgException ex = pgException("22003", "numeric field overflow");
            InvalidDataAccessUsageException result =
                    assertInstanceOf(InvalidDataAccessUsageException.class, mapper.translate(ex, "query failed"));
            assertEquals("22003", result.sqlState());
        }

        @Test
        @DisplayName("42xxx (syntax/access violation) → InvalidDataAccessUsageException")
        void shouldMapSyntaxError() {
            PgException ex = pgException("42601", "syntax error at or near \"SELCT\"");
            InvalidDataAccessUsageException result =
                    assertInstanceOf(InvalidDataAccessUsageException.class, mapper.translate(ex, "query failed"));
            assertEquals("42601", result.sqlState());
        }

        @Test
        @DisplayName("28xxx (invalid authorization) → InvalidDataAccessUsageException")
        void shouldMapInvalidAuthorization() {
            PgException ex = pgException("28000", "password authentication failed");
            InvalidDataAccessUsageException result =
                    assertInstanceOf(InvalidDataAccessUsageException.class, mapper.translate(ex, "connect failed"));
            assertEquals("28000", result.sqlState());
        }

        @Test
        @DisplayName("25xxx (invalid transaction state) → InvalidDataAccessUsageException")
        void shouldMapInvalidTransactionState() {
            PgException ex = pgException("25001", "active SQL transaction");
            InvalidDataAccessUsageException result =
                    assertInstanceOf(InvalidDataAccessUsageException.class, mapper.translate(ex, "tx error"));
            assertEquals("25001", result.sqlState());
        }

        @Test
        @DisplayName("54xxx (program limit exceeded) → InvalidDataAccessUsageException")
        void shouldMapProgramLimitExceeded() {
            PgException ex = pgException("54001", "statement too complex");
            InvalidDataAccessUsageException result =
                    assertInstanceOf(InvalidDataAccessUsageException.class, mapper.translate(ex, "query failed"));
            assertEquals("54001", result.sqlState());
        }

        @Test
        @DisplayName("53xxx (insufficient resources) → TransientDataAccessException")
        void shouldMapInsufficientResources() {
            PgException ex = pgException("53100", "disk full");
            TransientDataAccessException result =
                    assertInstanceOf(TransientDataAccessException.class, mapper.translate(ex, "write failed"));
            assertEquals("53100", result.sqlState());
        }

        @Test
        @DisplayName("58xxx (system error) → TransientDataAccessException")
        void shouldMapSystemError() {
            PgException ex = pgException("58030", "I/O error");
            TransientDataAccessException result =
                    assertInstanceOf(TransientDataAccessException.class, mapper.translate(ex, "query failed"));
            assertEquals("58030", result.sqlState());
        }
    }

    // --- Operator intervention (57P0x) ---

    @Nested
    @DisplayName("57P0x — operator intervention")
    class OperatorIntervention {

        @Test
        @DisplayName("57P01 (admin shutdown) → ConnectionException")
        void shouldMapAdminShutdown() {
            PgException ex = pgException("57P01", "terminating connection due to administrator command");
            ConnectionException result =
                    assertInstanceOf(ConnectionException.class, mapper.translate(ex, "query interrupted"));
            assertEquals("57P01", result.sqlState());
        }

        @Test
        @DisplayName("57P02 (crash recovery) → ConnectionException")
        void shouldMapCrashRecovery() {
            PgException ex = pgException("57P02", "terminating connection due to crash of another server process");
            ConnectionException result =
                    assertInstanceOf(ConnectionException.class, mapper.translate(ex, "connection lost"));
            assertEquals("57P02", result.sqlState());
        }

        @Test
        @DisplayName("57P03 (cannot connect now) → ConnectionException")
        void shouldMapCannotConnectNow() {
            PgException ex = pgException("57P03", "the database system is starting up");
            ConnectionException result =
                    assertInstanceOf(ConnectionException.class, mapper.translate(ex, "connect failed"));
            assertEquals("57P03", result.sqlState());
        }

        @Test
        @DisplayName("57P04 (database dropped) → ConnectionException")
        void shouldMapDatabaseDropped() {
            PgException ex = pgException("57P04", "database dropped");
            ConnectionException result =
                    assertInstanceOf(ConnectionException.class, mapper.translate(ex, "connect failed"));
            assertEquals("57P04", result.sqlState());
        }
    }

    // --- Bug fixes ---

    @Nested
    @DisplayName("Bug fixes")
    class BugFixes {

        @Test
        @DisplayName("57014 should preserve SQL state code")
        void shouldPreserveSqlStateOn57014() {
            PgException ex = pgException("57014", "canceling statement due to statement timeout");
            QueryTimeoutException result =
                    assertInstanceOf(QueryTimeoutException.class, mapper.translate(ex, "timeout"));
            assertEquals("57014", result.sqlState(), "SQL state must be preserved");
        }

        @Test
        @DisplayName("VertxException with 'Connection acquisition timeout' → QueryTimeoutException")
        void shouldMapVertxTimeoutCaseInsensitive() {
            VertxException ex = new VertxException("Connection acquisition timeout", true);
            assertInstanceOf(QueryTimeoutException.class, mapper.translate(ex, "pool timeout"));
        }

        @Test
        @DisplayName("VertxException with 'Pool TIMEOUT reached' → QueryTimeoutException")
        void shouldMapVertxTimeoutMixedCase() {
            VertxException ex = new VertxException("Pool TIMEOUT reached", true);
            assertInstanceOf(QueryTimeoutException.class, mapper.translate(ex, "pool timeout"));
        }
    }

    // --- Pass-through and catch-all ---

    @Test
    @DisplayName("existing DataAccessException passes through unchanged without double-wrapping")
    void shouldPassThroughDataAccessException() {
        UniqueConstraintViolationException original = new UniqueConstraintViolationException("dup");
        assertSame(original, mapper.translate(original, "should not wrap"));
    }

    @Test
    @DisplayName("unmapped exception type → DataAccessException fallback with context and cause preserved")
    void shouldCatchAllUnknownExceptions() {
        IllegalStateException ex = new IllegalStateException("unknown error");
        DataAccessException result = assertInstanceOf(DataAccessException.class, mapper.translate(ex, "wrapped"));
        assertEquals("wrapped", result.getMessage());
        assertInstanceOf(IllegalStateException.class, result.getCause());
    }
}
