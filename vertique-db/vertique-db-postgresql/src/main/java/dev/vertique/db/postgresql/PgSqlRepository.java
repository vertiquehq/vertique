// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import dev.vertique.db.AbstractSqlRepository;
import dev.vertique.db.query.OffsetPagedQuery;
import dev.vertique.db.query.PagedQuery;
import dev.vertique.db.query.Query;
import io.vertx.sqlclient.Pool;

/**
 * PostgreSQL repository base class. Extends {@link AbstractSqlRepository} and provides a {@link
 * #query()} factory method returning a {@link PgQuery.Builder} pre-configured with this
 * repository's pool and exception mapper.
 *
 * <p>Application repositories extend this class:
 *
 * <pre>{@code
 * public class ItemRepository extends PgSqlRepository {
 *
 *     @Inject
 *     public ItemRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
 *         super(pool, exceptionMapper);
 *     }
 *
 *     public Future<Item> findById(UUID id) {
 *         return this.<Item>query("SELECT id, name, description FROM items WHERE id = $1")
 *             .params(Tuple.of(id))
 *             .mapping(Item::fromRow)
 *             .one()
 *             .map(opt -> opt.orElse(null));
 *     }
 * }
 * }</pre>
 */
public class PgSqlRepository extends AbstractSqlRepository {

    /**
     * Creates a new PostgreSQL repository.
     *
     * @param pool          the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL exception mapper
     */
    public PgSqlRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
        super(pool, exceptionMapper);
    }

    /**
     * Returns a new {@link PgQuery.Builder} pre-configured with this repository's pool and failure
     * mapper. The builder validates that lock clauses are applied via {@link PgLockMode} rather than
     * embedded in the SQL string.
     *
     * @param <T> the domain type (inferred from the mapper)
     * @return a new PostgreSQL query builder
     */
    @Override
    public <T> Query.Builder<T, ?> query() {
        return PgQuery.<T>builder().on(pool).exceptionMapper(exceptionMapper);
    }

    /**
     * Returns a new {@link PgPagedQuery.Builder} pre-configured with this repository's pool and
     * exception mapper. The builder validates that the base SQL does not contain ORDER BY, LIMIT,
     * OFFSET, or inline lock clauses.
     *
     * @param <T> the domain type (inferred from the mapper)
     * @return a new PostgreSQL paged query builder
     */
    @Override
    public <T> PagedQuery.Builder<T, ?> pagedQuery() {
        return PgPagedQuery.<T>builder().on(pool).exceptionMapper(exceptionMapper);
    }

    /**
     * Returns a new {@link PgOffsetPagedQuery.Builder} pre-configured with this repository's pool
     * and exception mapper. The builder validates that the base SQL does not contain ORDER BY, LIMIT,
     * OFFSET, or inline lock clauses.
     *
     * @param <T> the domain type (inferred from the mapper)
     * @return a new PostgreSQL offset-paged query builder
     */
    @Override
    public <T> OffsetPagedQuery.Builder<T, ?> offsetPagedQuery() {
        return PgOffsetPagedQuery.<T>builder().on(pool).exceptionMapper(exceptionMapper);
    }
}
