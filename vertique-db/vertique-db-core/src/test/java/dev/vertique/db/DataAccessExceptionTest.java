// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.exception.ConcurrencyFailureException;
import dev.vertique.db.exception.ConnectionException;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.exception.DataIntegrityViolationException;
import dev.vertique.db.exception.DeadlockException;
import dev.vertique.db.exception.ForeignKeyViolationException;
import dev.vertique.db.exception.IncorrectResultSizeDataAccessException;
import dev.vertique.db.exception.InvalidDataAccessUsageException;
import dev.vertique.db.exception.OptimisticLockingFailureException;
import dev.vertique.db.exception.PessimisticLockingFailureException;
import dev.vertique.db.exception.QueryTimeoutException;
import dev.vertique.db.exception.TransientDataAccessException;
import dev.vertique.db.exception.UniqueConstraintViolationException;
import org.junit.jupiter.api.Test;

class DataAccessExceptionTest {

    @Test
    void hierarchyShouldBeCorrect() {
        assertInstanceOf(RuntimeException.class, new DataAccessException("test"));
        assertInstanceOf(DataAccessException.class, new TransientDataAccessException("test"));
        assertInstanceOf(DataAccessException.class, new DataIntegrityViolationException("test"));
        assertInstanceOf(DataAccessException.class, new ConcurrencyFailureException("test"));
        assertInstanceOf(DataAccessException.class, new InvalidDataAccessUsageException("test"));
    }

    @Test
    void transientExceptionsShouldExtendTransient() {
        assertInstanceOf(TransientDataAccessException.class, new ConnectionException("test"));
        assertInstanceOf(TransientDataAccessException.class, new QueryTimeoutException("test"));
        assertInstanceOf(TransientDataAccessException.class, new DeadlockException("test"));
    }

    @Test
    void integrityExceptionsShouldExtendIntegrity() {
        assertInstanceOf(DataIntegrityViolationException.class, new UniqueConstraintViolationException("test"));
        assertInstanceOf(DataIntegrityViolationException.class, new ForeignKeyViolationException("test"));
    }

    @Test
    void concurrencyExceptionsShouldExtendConcurrency() {
        assertInstanceOf(ConcurrencyFailureException.class, new OptimisticLockingFailureException("test"));
        assertInstanceOf(ConcurrencyFailureException.class, new PessimisticLockingFailureException("test"));
    }

    @Test
    void contextFieldsShouldBeNullByDefault() {
        var ex = new DataAccessException("test");
        assertNull(ex.sqlState());
        assertNull(ex.constraintName());
        assertNull(ex.tableName());
    }

    @Test
    void contextFieldsShouldBeSetWhenProvided() {
        var ex = new UniqueConstraintViolationException("dup", new RuntimeException(), "23505", "uk_email", "users");
        assertEquals("23505", ex.sqlState());
        assertEquals("uk_email", ex.constraintName());
        assertEquals("users", ex.tableName());
        assertEquals("dup", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void sqlStateConstructorShouldWork() {
        var ex = new ConnectionException("timeout", new RuntimeException(), "08001");
        assertEquals("08001", ex.sqlState());
        assertNull(ex.constraintName());
        assertNull(ex.tableName());
    }

    @Test
    void incorrectResultSizeShouldExtendInvalidDataAccessUsageException() {
        var ex = new IncorrectResultSizeDataAccessException("test", 1, 5);
        assertInstanceOf(InvalidDataAccessUsageException.class, ex);
        assertInstanceOf(DataAccessException.class, ex);
        assertEquals(1, ex.expectedSize());
        assertEquals(5, ex.actualSize());
        assertEquals("test", ex.getMessage());
    }

    @Test
    void incorrectResultSizeWithUnknownActual() {
        var ex = new IncorrectResultSizeDataAccessException("test", 1, -1);
        assertEquals(-1, ex.actualSize());
    }
}
