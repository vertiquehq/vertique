// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.db.exception.DataAccessException;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.DatabaseException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PgWorkflowExceptionMapper} (stage-1, DB boundary).
 *
 * <p>Verifies the registered-translator precedence and the {@code fallback} override: the
 * {@code on(WorkflowException.class, ...)} registration covers only workflow business-rule subtypes;
 * other workflow-semantic exceptions ({@link dev.vertique.workflow.exception.WorkflowConflictException}
 * and the not-found / technical / unavailable roots) extend core exception roots, not
 * {@link dev.vertique.workflow.exception.WorkflowException}, so they pass through via the overridden
 * {@code fallback}. Unknown non-DB throwables also pass through via {@code fallback}. Genuine DB
 * driver exceptions are mapped to {@link DataAccessException} subtypes by the inherited PostgreSQL
 * rules.
 */
class PgWorkflowExceptionMapperPrecedenceTest {

    /**
     * A reparented workflow not-found exception ({@link WorkflowInstanceNotFoundException} extends
     * {@link dev.vertique.workflow.exception.WorkflowNotFoundException} → core
     * {@link dev.vertique.core.exception.NotFoundException}, not {@link
     * dev.vertique.workflow.exception.WorkflowException}) must pass through unchanged via the
     * {@link PgWorkflowExceptionMapper#fallback(Throwable, String) fallback} override, since it is not
     * matched by {@code on(WorkflowException.class, ...)}.
     */
    @Test
    @DisplayName("translate(WorkflowInstanceNotFoundException) returns the same instance (fallback, not on() guard)")
    void workflowExceptionTranslatesToSelf() {
        PgWorkflowExceptionMapper mapper = new PgWorkflowExceptionMapper();
        WorkflowInstanceNotFoundException input =
                new WorkflowInstanceNotFoundException(new WorkflowInstanceId(UUID.randomUUID()));

        Throwable result = mapper.translate(input, "op");

        assertSame(input, result, "reparented workflow not-found must pass through unchanged via fallback");
    }

    /**
     * An unknown non-DB throwable (a programmer error) must be returned unchanged by the
     * {@code fallback} override, NOT wrapped in a {@link DataAccessException}.
     */
    @Test
    @DisplayName("translate(IllegalStateException) returns the same instance (fallback override)")
    void illegalStateExceptionTranslatesToSelf() {
        PgWorkflowExceptionMapper mapper = new PgWorkflowExceptionMapper();
        IllegalStateException input = new IllegalStateException("bug");

        Throwable result = mapper.translate(input, "op");

        assertSame(input, result, "unknown non-DB throwable must pass through unchanged (fallback override)");
    }

    /**
     * A {@link WorkflowConflictException} (extends core {@link dev.vertique.core.exception.ConflictException},
     * not {@link dev.vertique.workflow.exception.WorkflowException}) must pass through unchanged via the
     * {@link PgWorkflowExceptionMapper#fallback(Throwable, String) fallback} override — it is a
     * reparented workflow-semantic type that is not matched by {@code on(WorkflowException.class, ...)}.
     */
    @Test
    @DisplayName("translate(WorkflowConflictException) returns the same instance (fallback, reparented type)")
    void workflowConflictExceptionPassesThroughViaFallback() {
        PgWorkflowExceptionMapper mapper = new PgWorkflowExceptionMapper();
        WorkflowConflictException input = new WorkflowConflictException("optimistic conflict");

        Throwable result = mapper.translate(input, "op");

        assertSame(input, result, "reparented WorkflowConflictException must pass through unchanged via fallback");
    }

    /**
     * A {@link DatabaseException} from the driver (here a {@link PgException} subtype) must be mapped
     * to a {@link DataAccessException} subtype by the inherited PostgreSQL rules.
     */
    @Test
    @DisplayName("translate(DatabaseException) maps to a DataAccessException subtype")
    void databaseExceptionTranslatesToDataAccessExceptionSubtype() {
        PgWorkflowExceptionMapper mapper = new PgWorkflowExceptionMapper();
        DatabaseException input = new PgException("conn lost", "ERROR", "08006", null);

        Throwable result = mapper.translate(input, "op");

        assertInstanceOf(DataAccessException.class, result, "DatabaseException must map to a DataAccessException");
    }
}
