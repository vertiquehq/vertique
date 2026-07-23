// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import dev.vertique.db.query.Query;
import java.util.regex.Pattern;

/**
 * PostgreSQL implementation of {@link Query}. Validates that the base SQL does not contain
 * PostgreSQL-specific trailing clauses (FOR UPDATE/SHARE, SKIP LOCKED, NOWAIT) which must be
 * applied via {@link PgLockMode} using {@link Builder#queryClause}.
 *
 * <pre>{@code
 * // Simple query
 * query("SELECT id, name FROM items WHERE id = $1")
 *     .params(Tuple.of(itemId))
 *     .mapping(row -> new Item(row.getUUID("id"), row.getString("name")))
 *     .one();
 *
 * // With lock mode in a transaction
 * transaction().execute(conn ->
 *     query("SELECT id, name FROM items WHERE id = $1")
 *         .on(conn)
 *         .params(Tuple.of(itemId))
 *         .mapping(Item::fromRow)
 *         .queryClause(PgLockMode.FOR_UPDATE)
 *         .one()
 * );
 * }</pre>
 *
 * @param <T> the domain type
 * @see PgSqlRepository#query(String)
 */
public final class PgQuery<T> extends Query<T> {

    private PgQuery(Builder<T> builder) {
        super(builder);
        validatePgBaseSql(sql());
    }

    private static final Pattern LOCK_PATTERN =
            Pattern.compile("\\bFOR\\s+(UPDATE|SHARE|NO\\s+KEY\\s+UPDATE|KEY\\s+SHARE)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SKIP_LOCKED_PATTERN =
            Pattern.compile("\\bSKIP\\s+LOCKED\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOWAIT_PATTERN = Pattern.compile("\\bNOWAIT\\b", Pattern.CASE_INSENSITIVE);

    private static void validatePgBaseSql(String sql) {
        if (LOCK_PATTERN.matcher(sql).find()) {
            throw new IllegalArgumentException(
                    "Base SQL must not contain FOR UPDATE/SHARE — use .queryClause(PgLockMode.FOR_UPDATE) instead.");
        }
        if (SKIP_LOCKED_PATTERN.matcher(sql).find()) {
            throw new IllegalArgumentException("Base SQL must not contain SKIP LOCKED"
                    + " — use .queryClause(PgLockMode.FOR_UPDATE_SKIP_LOCKED) instead.");
        }
        if (NOWAIT_PATTERN.matcher(sql).find()) {
            throw new IllegalArgumentException(
                    "Base SQL must not contain NOWAIT" + " — use .queryClause(PgLockMode.FOR_UPDATE_NOWAIT) instead.");
        }
    }

    /**
     * Creates a new builder for a PostgreSQL query.
     *
     * @param <T> the domain type
     * @return a new builder
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Builder for {@link PgQuery}.
     *
     * @param <T> the domain type
     */
    public static final class Builder<T> extends Query.Builder<T, Builder<T>> {

        Builder() {}

        @Override
        protected Builder<T> self() {
            return this;
        }

        @Override
        protected PgQuery<T> build() {
            return new PgQuery<>(this);
        }
    }
}
