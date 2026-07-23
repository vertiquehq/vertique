// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.exception.InvalidDataAccessUsageException;
import dev.vertique.db.exception.UniqueConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link DbExceptionMapper}: hierarchy walk to find the most-specific registered
 * translator, context propagation, DataAccessException pass-through, the generic fallback wrapper
 * for unmapped throwables, and the widened {@code Throwable} return type that allows DB translators
 * to return non-{@link DataAccessException} business exceptions.
 */
class DbExceptionMapperTest {

    private DbExceptionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new DbExceptionMapper();
    }

    @Test
    @DisplayName("translates using the exact-type translator and preserves context and cause")
    void shouldTranslateExactType() {
        mapper.on(IllegalArgumentException.class, (e, ctx) -> new InvalidDataAccessUsageException(ctx, e));

        DataAccessException result = assertInstanceOf(
                InvalidDataAccessUsageException.class,
                mapper.translate(new IllegalArgumentException("bad sql"), "query failed"));
        assertEquals("query failed", result.getMessage());
        assertInstanceOf(IllegalArgumentException.class, result.getCause());
    }

    @Test
    @DisplayName("walks the superclass hierarchy to find the first matching translator")
    void shouldWalkHierarchyToFindTranslator() {
        mapper.on(RuntimeException.class, (e, ctx) -> new DataAccessException(ctx, e));

        // IllegalStateException extends RuntimeException — should match
        assertInstanceOf(DataAccessException.class, mapper.translate(new IllegalStateException("state")));
    }

    @Test
    @DisplayName("prefers the most specific (closest-match) translator over a more general one")
    void shouldPreferMostSpecificTranslator() {
        mapper.on(RuntimeException.class, (e, ctx) -> new DataAccessException("generic", e));
        mapper.on(IllegalArgumentException.class, (e, ctx) -> new InvalidDataAccessUsageException("specific", e));

        DataAccessException result = assertInstanceOf(
                InvalidDataAccessUsageException.class, mapper.translate(new IllegalArgumentException("bad")));
        assertEquals("specific", result.getMessage());
    }

    @Test
    @DisplayName("passes an existing DataAccessException through unchanged via a registered identity translator")
    void shouldPassThroughDataAccessExceptions() {
        var original = new UniqueConstraintViolationException("dup");
        mapper.on(DataAccessException.class, (e, ctx) -> e);

        assertSame(original, mapper.translate(original));
    }

    @Test
    @DisplayName("wraps an unmapped throwable in a generic DataAccessException when no translator is registered")
    void shouldFallbackToGenericWhenNoTranslatorRegistered() {
        // No translators registered — should wrap in generic DataAccessException
        DataAccessException result =
                assertInstanceOf(DataAccessException.class, mapper.translate(new IllegalStateException("unknown")));
        assertEquals("unknown", result.getMessage());
    }

    @Test
    @DisplayName("uses the throwable message as default context when no explicit context is provided")
    void shouldUseThrowableMessageAsDefaultContext() {
        mapper.on(RuntimeException.class, (e, ctx) -> new DataAccessException(ctx, e));

        DataAccessException result =
                assertInstanceOf(DataAccessException.class, mapper.translate(new RuntimeException("original msg")));
        assertEquals("original msg", result.getMessage());
    }

    @Test
    @DisplayName(
            "a Throwable-level catch-all translator handles checked exceptions not covered by narrower registrations")
    void catchAllShouldHandleAnyException() {
        mapper.on(Throwable.class, (e, ctx) -> new DataAccessException(ctx, e));

        DataAccessException result =
                assertInstanceOf(DataAccessException.class, mapper.translate(new Exception("checked"), "op failed"));
        assertEquals("op failed", result.getMessage());
        assertInstanceOf(Exception.class, result.getCause());
    }

    @Test
    @DisplayName("a DB translator may return a non-DataAccessException business exception")
    void translateCanReturnNonDataAccessBusinessException() {
        var mapper = new DbExceptionMapper();
        mapper.on(IllegalStateException.class, (e, ctx) -> new BusinessException(ctx, e));

        Throwable r = mapper.translate(new IllegalStateException("x"), "op");
        assertInstanceOf(BusinessException.class, r);
    }

    @Test
    @DisplayName("wraps an unmapped throwable in DataAccessException via fallback, preserving context and cause")
    void unmappedThrowableWrappedInDataAccessException() {
        DataAccessException r = assertInstanceOf(
                DataAccessException.class, new DbExceptionMapper().translate(new java.io.IOException("io"), "op"));
        assertEquals("op", r.getMessage());
        assertInstanceOf(java.io.IOException.class, r.getCause());
    }

    @Test
    @DisplayName("passes an existing DataAccessException through unchanged without double-wrapping")
    void existingDataAccessExceptionPassesThrough() {
        DataAccessException dae = new DataAccessException("x");
        assertSame(dae, new DbExceptionMapper().translate(dae, "op"));
    }

    @Test
    @DisplayName("on(...) returns DbExceptionMapper to allow fluent chaining of translator registrations")
    void onReturnsSubtypeForChaining() {
        DbExceptionMapper m = new DbExceptionMapper().on(IllegalStateException.class, (e, ctx) -> e);
        assertNotNull(m);
    }

    /** A non-{@link DataAccessException} business exception, proving a DB translator may return any throwable. */
    static class BusinessException extends RuntimeException {
        BusinessException(String m, Throwable c) {
            super(m, c);
        }
    }
}
