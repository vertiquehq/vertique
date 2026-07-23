// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.workflow.exception.WorkflowException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Stage-1 (DB-boundary) exception mapper for the workflow transaction runner.
 *
 * <p>Applied <em>inside</em> {@code TransactionBuilder.execute(...)} via the runner's dedicated
 * repository. It keeps the database layer honest about database failures while never misclassifying
 * non-DB throwables:
 *
 * <ul>
 *   <li><b>{@link WorkflowException}</b> (the business-rule root and its subtypes —
 *       {@code WorkflowSubjectVersionUnavailableException}, {@code WorkflowMigrationIllegalStateException},
 *       {@code WorkflowMigrationHandlerMissingException}) passes through unchanged — registered via
 *       {@code on(WorkflowException.class, (e, ctx) -> e)}. Note: other workflow-semantic exceptions
 *       ({@code WorkflowConflictException}, {@code WorkflowInstanceNotFoundException}, etc.) extend core
 *       exception roots, not {@link WorkflowException}, so they are not matched by this registration;
 *       they pass through the inherited DB-translation rules unchanged (no match) and are returned by
 *       the overridden {@link #fallback(Throwable, String)}.</li>
 *   <li><b>{@link io.vertx.sqlclient.DatabaseException} / {@link io.vertx.pgclient.PgException}</b>
 *       are mapped to {@link DataAccessException} subtypes by the inherited PostgreSQL rules
 *       ({@link PgDbExceptionMapper}); the more specific registered translator wins via the
 *       superclass walk.</li>
 *   <li><b>Any other unmapped throwable</b> (programmer or configuration errors such as
 *       {@link IllegalStateException}) is returned <em>unchanged</em> — this mapper overrides
 *       {@link #fallback(Throwable, String)} to return the throwable as-is rather than wrapping it in
 *       a {@link DataAccessException}.</li>
 * </ul>
 *
 * <p>Deliberately does <b>not</b> register {@code on(Throwable.class, ...)}: the {@code fallback}
 * override is what implements the unknown-passthrough policy, leaving the DB-specific subtype rules
 * (registered for {@code DatabaseException}/{@code VertxException}) to take precedence for genuine
 * DB failures.
 *
 * <p>Stage 2 (the workflow-domain translation of {@link DataAccessException} into workflow-semantic
 * exceptions, rooted in the core hierarchy) is performed separately by {@code WorkflowExceptionMapper}
 * in the runner's outer {@code recover}.
 */
@Singleton
class WorkflowPgExceptionMapper extends PgDbExceptionMapper {

    /**
     * Creates a mapper that adds {@link WorkflowException} passthrough on top of the inherited
     * PostgreSQL SQL-state translations, with an unknown-passthrough {@code fallback}.
     */
    @Inject
    WorkflowPgExceptionMapper() {
        on(WorkflowException.class, (e, ctx) -> e);
    }

    /**
     * Returns the throwable unchanged. Overrides the {@link dev.vertique.db.DbExceptionMapper}
     * default (which wraps
     * unmapped throwables in {@link DataAccessException}) so that non-DB throwables — programmer and
     * configuration errors — are not misclassified as database failures at the DB boundary.
     *
     * @param throwable the untranslated exception
     * @param context   contextual message describing the failed operation (unused)
     * @return {@code throwable}, unchanged
     */
    @Override
    protected Throwable fallback(Throwable throwable, String context) {
        return throwable;
    }
}
