// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import dev.vertique.core.failure.ContextAwareFailureTranslator;
import dev.vertique.core.failure.FailureMapper;
import dev.vertique.core.failure.FailureTranslator;
import dev.vertique.db.exception.DataAccessException;

/**
 * Hierarchy-aware exception translator for the database layer. Translates throwables to
 * {@link DataAccessException} subtypes using registered translators.
 *
 * <p>Extends the shared {@link FailureMapper} for the superclass-walk and caching logic, overriding
 * {@link #fallback(Throwable, String)} to wrap any unmapped throwable in a generic
 * {@link DataAccessException}. Vendor modules extend this class with pre-configured translations
 * (e.g., {@code PgDbExceptionMapper}).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var mapper = new DbExceptionMapper();
 * mapper.on(DatabaseException.class, (e, ctx) -> new DataAccessException(ctx, e));
 * }</pre>
 */
public class DbExceptionMapper extends FailureMapper {

    /**
     * Creates a mapper that passes existing {@link DataAccessException}s through unchanged; any other
     * unmapped throwable is wrapped by {@link #fallback(Throwable, String)}.
     */
    public DbExceptionMapper() {
        on(DataAccessException.class, (e, ctx) -> e);
    }

    @Override
    public <T extends Throwable> DbExceptionMapper on(Class<T> type, FailureTranslator<T> translator) {
        super.on(type, translator);
        return this;
    }

    @Override
    public <T extends Throwable> DbExceptionMapper on(Class<T> type, ContextAwareFailureTranslator<T> translator) {
        super.on(type, translator);
        return this;
    }

    @Override
    protected Throwable fallback(Throwable throwable, String context) {
        return new DataAccessException(context, throwable);
    }
}
