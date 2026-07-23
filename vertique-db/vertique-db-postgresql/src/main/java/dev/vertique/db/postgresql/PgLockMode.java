// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import dev.vertique.db.query.QueryClause;

/**
 * PostgreSQL row-level lock modes, appended after the base SQL (or after the {@code LIMIT} clause
 * in paginated queries).
 *
 * <p>PostgreSQL supports four lock strengths:
 *
 * <ul>
 *   <li>{@code FOR UPDATE} — exclusive row lock, blocks other FOR UPDATE/SHARE/NO KEY UPDATE
 *   <li>{@code FOR NO KEY UPDATE} — weaker exclusive lock, allows concurrent FOR KEY SHARE
 *   <li>{@code FOR SHARE} — shared lock, blocks FOR UPDATE/NO KEY UPDATE
 *   <li>{@code FOR KEY SHARE} — weakest lock, only blocks FOR UPDATE
 * </ul>
 *
 * <p>Each lock strength can be combined with a wait policy:
 *
 * <ul>
 *   <li><b>Default</b> — waits for locked rows
 *   <li>{@code SKIP LOCKED} — silently skips rows locked by other transactions (work-queue pattern)
 *   <li>{@code NOWAIT} — raises an error immediately if a row is locked (fast-fail)
 * </ul>
 *
 * <pre>{@code
 * // Exclusive lock inside a transaction
 * transaction().execute(conn ->
 *     query("SELECT id, name FROM items WHERE id = $1")
 *         .on(conn)
 *         .params(Tuple.of(id))
 *         .mapping(Item::fromRow)
 *         .queryClause(PgLockMode.FOR_UPDATE)
 *         .one()
 * );
 *
 * // Work-queue: lock rows, skip already-locked ones
 * query("SELECT id, payload FROM work_queue WHERE status = $1")
 *     .params(Tuple.of("pending"))
 *     .mapping(Job::fromRow)
 *     .queryClause(PgLockMode.FOR_UPDATE_SKIP_LOCKED)
 *     .list();
 * }</pre>
 *
 * @see QueryClause
 */
public enum PgLockMode implements QueryClause {

    // -- Basic lock modes (wait for locked rows) --

    /** Exclusive row lock. Blocks other FOR UPDATE/SHARE/NO KEY UPDATE. */
    FOR_UPDATE("FOR UPDATE"),

    /** Weaker exclusive lock. Allows concurrent FOR KEY SHARE. */
    FOR_NO_KEY_UPDATE("FOR NO KEY UPDATE"),

    /** Shared row lock. Blocks FOR UPDATE/NO KEY UPDATE. */
    FOR_SHARE("FOR SHARE"),

    /** Weakest lock. Only blocks FOR UPDATE. */
    FOR_KEY_SHARE("FOR KEY SHARE"),

    // -- Lock modes with SKIP LOCKED --

    /** Exclusive lock; silently skip rows locked by other transactions. Work-queue pattern. */
    FOR_UPDATE_SKIP_LOCKED("FOR UPDATE SKIP LOCKED"),

    /** Weaker exclusive lock; skip locked rows. */
    FOR_NO_KEY_UPDATE_SKIP_LOCKED("FOR NO KEY UPDATE SKIP LOCKED"),

    /** Shared lock; skip locked rows. */
    FOR_SHARE_SKIP_LOCKED("FOR SHARE SKIP LOCKED"),

    /** Weakest lock; skip locked rows. */
    FOR_KEY_SHARE_SKIP_LOCKED("FOR KEY SHARE SKIP LOCKED"),

    // -- Lock modes with NOWAIT --

    /** Exclusive lock; error immediately if any row is locked. */
    FOR_UPDATE_NOWAIT("FOR UPDATE NOWAIT"),

    /** Weaker exclusive lock; error immediately if any row is locked. */
    FOR_NO_KEY_UPDATE_NOWAIT("FOR NO KEY UPDATE NOWAIT"),

    /** Shared lock; error immediately if any row is locked. */
    FOR_SHARE_NOWAIT("FOR SHARE NOWAIT"),

    /** Weakest lock; error immediately if any row is locked. */
    FOR_KEY_SHARE_NOWAIT("FOR KEY SHARE NOWAIT");

    private final String sql;

    PgLockMode(String sql) {
        this.sql = sql;
    }

    @Override
    public String sql() {
        return sql;
    }
}
