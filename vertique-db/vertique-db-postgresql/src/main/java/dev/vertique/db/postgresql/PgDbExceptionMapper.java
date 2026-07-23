// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import dev.vertique.db.DbExceptionMapper;
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
import io.vertx.sqlclient.DatabaseException;
import java.util.Locale;

/**
 * PostgreSQL-specific exception mapper with SQL state translations.
 *
 * <p>Pre-configured translations:
 *
 * <ul>
 *   <li>SQL state class {@code 08} (connection exception) → {@link ConnectionException}
 *   <li>SQL state {@code 23503} (foreign key violation) → {@link ForeignKeyViolationException}
 *   <li>SQL state {@code 23502} (NOT NULL violation) → {@link DataIntegrityViolationException}
 *   <li>SQL state {@code 23505} (unique violation) → {@link UniqueConstraintViolationException}
 *   <li>SQL state {@code 23514} (CHECK constraint violation) → {@link DataIntegrityViolationException}
 *   <li>SQL state {@code 23P01} (exclusion constraint violation) → {@link DataIntegrityViolationException}
 *   <li>SQL state {@code 40001} (serialization failure) → {@link OptimisticLockingFailureException}
 *   <li>SQL state {@code 40P01} (deadlock detected) → {@link DeadlockException}
 *   <li>SQL state {@code 55P03} (lock not available) → {@link PessimisticLockingFailureException}
 *   <li>SQL state {@code 57014} (statement timeout) → {@link QueryTimeoutException}
 *   <li>SQL state {@code 57P01} (admin shutdown) → {@link ConnectionException}
 *   <li>SQL state {@code 57P02} (crash recovery) → {@link ConnectionException}
 *   <li>SQL state {@code 57P03} (cannot connect now) → {@link ConnectionException}
 *   <li>SQL state {@code 57P04} (database dropped) → {@link ConnectionException}
 *   <li>SQL state class {@code 22} (data exception) → {@link InvalidDataAccessUsageException}
 *   <li>SQL state class {@code 25} (invalid transaction state) → {@link InvalidDataAccessUsageException}
 *   <li>SQL state class {@code 28} (invalid authorization specification) → {@link InvalidDataAccessUsageException}
 *   <li>SQL state class {@code 42} (syntax error or access rule violation) → {@link InvalidDataAccessUsageException}
 *   <li>SQL state class {@code 54} (program limit exceeded) → {@link InvalidDataAccessUsageException}
 *   <li>SQL state class {@code 53} (insufficient resources) → {@link TransientDataAccessException}
 *   <li>SQL state class {@code 58} (system error) → {@link TransientDataAccessException}
 *   <li>{@link VertxException} with message containing {@code "timeout"} (case-insensitive) → {@link QueryTimeoutException}
 * </ul>
 *
 * <p>Pass-through of existing {@link DataAccessException}s and the catch-all wrapping of any other
 * {@link Throwable} are inherited from {@link DbExceptionMapper}.
 *
 * <p>When the exception is a {@link PgException}, constraint name and table name are extracted and
 * set on the resulting exception.
 */
public class PgDbExceptionMapper extends DbExceptionMapper {

    /** Creates a mapper with all PostgreSQL-specific translations pre-registered. */
    public PgDbExceptionMapper() {
        on(DatabaseException.class, this::mapDatabaseException);
        on(VertxException.class, this::mapVertxException);
    }

    /**
     * Maps a {@link DatabaseException} to the appropriate {@link DataAccessException} subclass based
     * on the SQL state code. Specific state codes take precedence over class-level prefix matching.
     *
     * @param e   the database exception to map
     * @param ctx contextual message for the translated exception
     * @return the mapped data access exception
     */
    private DataAccessException mapDatabaseException(DatabaseException e, String ctx) {
        String sqlState = e.getSqlState();
        String constraint = (e instanceof PgException pg) ? pg.getConstraint() : null;
        String table = (e instanceof PgException pg) ? pg.getTable() : null;

        if (sqlState != null && sqlState.startsWith("08")) {
            return new ConnectionException(ctx, e, sqlState);
        }
        return switch (sqlState) {
            case "23503" -> new ForeignKeyViolationException(ctx, e, sqlState, constraint, table);
            case "23505" -> new UniqueConstraintViolationException(ctx, e, sqlState, constraint, table);
            case "23502", "23514", "23P01" -> new DataIntegrityViolationException(ctx, e, sqlState, constraint, table);
            case "40001" -> new OptimisticLockingFailureException(ctx, e, sqlState);
            case "40P01" -> new DeadlockException(ctx, e, sqlState);
            case "55P03" -> new PessimisticLockingFailureException(ctx, e, sqlState);
            case "57014" -> new QueryTimeoutException(ctx, e, sqlState);
            case "57P01", "57P02", "57P03", "57P04" -> new ConnectionException(ctx, e, sqlState);
            case null -> new DataAccessException(ctx, e, sqlState);
            default -> mapByStateClass(ctx, e, sqlState);
        };
    }

    /**
     * Maps a {@link DatabaseException} to a typed exception based on the SQL state class (first two
     * characters). Called for SQL states not matched by an explicit case in
     * {@link #mapDatabaseException}.
     *
     * @param ctx      contextual message for the translated exception
     * @param e        the database exception to map
     * @param sqlState the non-null SQL state code
     * @return the mapped data access exception
     */
    private DataAccessException mapByStateClass(String ctx, DatabaseException e, String sqlState) {
        if (sqlState.startsWith("22")
                || sqlState.startsWith("25")
                || sqlState.startsWith("28")
                || sqlState.startsWith("42")
                || sqlState.startsWith("54")) {
            return new InvalidDataAccessUsageException(ctx, e, sqlState);
        }
        if (sqlState.startsWith("53") || sqlState.startsWith("58")) {
            return new TransientDataAccessException(ctx, e, sqlState);
        }
        return new DataAccessException(ctx, e, sqlState);
    }

    /**
     * Maps a {@link VertxException} to {@link QueryTimeoutException} when the message contains
     * {@code "timeout"} (case-insensitive), otherwise to a generic {@link DataAccessException}.
     *
     * @param e   the Vert.x exception to map
     * @param ctx contextual message for the translated exception
     * @return the mapped data access exception
     */
    private DataAccessException mapVertxException(VertxException e, String ctx) {
        String msg = e.getMessage();
        if (msg != null && msg.toLowerCase(Locale.ROOT).contains("timeout")) {
            return new QueryTimeoutException(ctx, e);
        }
        return new DataAccessException(ctx, e);
    }
}
