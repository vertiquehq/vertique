// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

/**
 * A vendor-specific SQL clause appended after the base SQL (or after the pagination LIMIT/FETCH
 * clause in paginated queries). Used for lock modes (PostgreSQL {@code FOR UPDATE/SHARE}) and
 * related modifiers like {@code SKIP LOCKED} or {@code NOWAIT}.
 *
 * <p>Implementations are typically enums in vendor-specific modules (e.g., {@code PgLockMode}).
 *
 * <p><strong>Security contract:</strong> Implementations must return a compile-time constant SQL
 * fragment. The {@code sql()} return value is interpolated directly into the final SQL string
 * without parameterization or escaping. Never construct the return value from user input, request
 * parameters, or any other untrusted dynamic source. All framework-provided implementations
 * (e.g., {@link dev.vertique.db.postgresql.PgLockMode}) satisfy this contract by using enum
 * constants with hardcoded SQL fragments.
 *
 * @see Query.Builder#queryClause(QueryClause)
 */
public interface QueryClause {

    /**
     * Returns the SQL fragment to append. The returned string must not include a leading space — the
     * framework adds one.
     *
     * <p><strong>Security:</strong> This value is interpolated as raw SQL. It must be a compile-time
     * constant, never derived from user input.
     *
     * @return the SQL fragment (e.g. {@code "FOR UPDATE SKIP LOCKED"})
     */
    String sql();
}
