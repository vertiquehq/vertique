// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the step that releases the PostgreSQL pool when the application stops. */
@DisplayName("PgPoolShutdownStep")
class PgPoolShutdownStepTest {

    private final Pool pool = mock(Pool.class);
    private final PgPoolShutdownStep step = new PgPoolShutdownStep(pool);

    @Test
    @DisplayName("stop closes the pool and completes when the pool has closed")
    void stopClosesThePool() {
        when(pool.close()).thenReturn(Future.succeededFuture());

        assertTrue(step.stop().succeeded());

        verify(pool).close();
    }

    @Test
    @DisplayName("a pool that fails to close fails the step, so the failure is visible to the shutdown run")
    void aCloseFailureIsReported() {
        IllegalStateException failure = new IllegalStateException("close failed");
        when(pool.close()).thenReturn(Future.failedFuture(failure));

        assertEquals(failure, step.stop().cause());
    }

    @Test
    @DisplayName("the step runs in the infrastructure phase behind same-phase consumers")
    void runsLastInTheInfrastructurePhase() {
        assertEquals(LifecyclePhase.INFRA, step.phase());
        assertEquals(Integer.MIN_VALUE, step.priority());
    }
}
