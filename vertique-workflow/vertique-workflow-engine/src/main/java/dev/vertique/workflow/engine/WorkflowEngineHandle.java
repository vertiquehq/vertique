// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowOperations;
import io.vertx.sqlclient.SqlClient;

/**
 * Public composite handle that re-bundles the four already-public workflow engine interfaces into a
 * single type, and exposes the execution probe of the same engine.
 *
 * <p>It exists as the return type of the {@link WorkflowEngineFactory} assembly seam so that callers outside the
 * engine package can hold a reference to a fully wired engine without the concrete {@link WorkflowEngine} (which
 * stays package-private). Production wiring uses Dagger to bind each constituent interface directly; the
 * factory is the parallel non-Dagger assembly path used by dialect/test code.
 *
 * <p>The transaction-handle type is fixed to {@link SqlClient}, matching the engine's
 * {@code SqlClient}-typed SPI stack.
 *
 * @see WorkflowEngineFactory
 * @see WorkflowEngine
 */
public interface WorkflowEngineHandle
        extends WorkflowOperations,
                TransactionalWorkflowOperations<SqlClient>,
                TransactionalTimerCallbacks<SqlClient>,
                TransactionalTaskCallbacks<SqlClient> {

    /**
     * The probe that waits until the commands recorded for an instance, or for every instance, have run.
     *
     * @return the probe built over the command repository this engine was assembled with
     */
    WorkflowExecutionProbe executionProbe();
}
