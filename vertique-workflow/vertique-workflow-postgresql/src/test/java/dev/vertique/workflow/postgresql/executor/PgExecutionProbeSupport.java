// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.workflow.engine.executor.DefaultWorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;

/** Builds an execution probe over a pool for tests that run no engine, whatever their package. */
public final class PgExecutionProbeSupport {

    private PgExecutionProbeSupport() {
        // static factory holder
    }

    /**
     * Builds a probe that reads the command log through {@code pool} and paces its polling with timers of
     * {@code vertx}.
     *
     * @param pool  the pool of the database under test
     * @param vertx the Vert.x instance that owns the pool
     * @return the probe
     */
    public static WorkflowExecutionProbe probe(Pool pool, Vertx vertx) {
        return new DefaultWorkflowExecutionProbe(
                new PgWorkflowCommandRepository(pool, new PgDbExceptionMapper()), vertx);
    }
}
