// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.postgresql.engine.PgWorkflowBranchRecoveryService;
import io.vertx.core.Future;
import jakarta.inject.Provider;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PgWorkflowBranchRecoveryCron}.
 *
 * <p>The impl is a thin adapter: it must forward the configured stale-threshold {@link Duration}
 * and batch size to {@link PgWorkflowBranchRecoveryService#sweepOnce(Duration, int)}, then map the
 * service's {@code Future<Integer>} count to {@code Future<Void>} while preserving failures.
 */
class PgWorkflowBranchRecoveryCronTest {

    /**
     * Builds an impl wired to the given mock recovery service with explicit batch size and stale
     * threshold.
     *
     * @param recoveryService the mock recovery service the impl will delegate to
     * @param batchSize       the configured batch size
     * @param staleThreshold  the configured stale-threshold {@link Duration}
     * @return the configured impl under test
     */
    private static PgWorkflowBranchRecoveryCron impl(
            PgWorkflowBranchRecoveryService recoveryService, int batchSize, Duration staleThreshold) {
        Provider<PgWorkflowBranchRecoveryService> provider = () -> recoveryService;
        return new PgWorkflowBranchRecoveryCron(provider, new WorkflowBranchRecoveryConfig(batchSize, staleThreshold));
    }

    @Nested
    @DisplayName("reconcile() — delegation")
    class Delegation {

        @Test
        @DisplayName("forwards configured staleThreshold + batchSize to sweepOnce(Duration, int)")
        void forwardsConfigToService() {
            PgWorkflowBranchRecoveryService service = mock(PgWorkflowBranchRecoveryService.class);
            when(service.sweepOnce(eq(Duration.ofMinutes(7)), eq(500))).thenReturn(Future.succeededFuture(0));

            Future<Void> result = impl(service, 500, Duration.ofMinutes(7)).reconcile();

            assertTrue(result.succeeded(), "reconcile() should succeed when service returns 0");
            verify(service).sweepOnce(eq(Duration.ofMinutes(7)), eq(500));
            verifyNoMoreInteractions(service);
        }

        @Test
        @DisplayName("mapEmpty() drops the integer count and returns Future<Void>")
        void mapsIntegerCountToVoid() {
            PgWorkflowBranchRecoveryService service = mock(PgWorkflowBranchRecoveryService.class);
            when(service.sweepOnce(eq(Duration.ofMinutes(5)), eq(1000))).thenReturn(Future.succeededFuture(42));

            Future<Void> result = impl(service, 1000, Duration.ofMinutes(5)).reconcile();

            assertTrue(result.succeeded(), "Future should succeed");
            // mapEmpty resolves to null; the count is intentionally dropped — the service itself
            // logs per-row outcomes.
        }

        @Test
        @DisplayName("service-level failure propagates through reconcile()")
        void servicefailurePropagates() {
            PgWorkflowBranchRecoveryService service = mock(PgWorkflowBranchRecoveryService.class);
            IllegalStateException boom = new IllegalStateException("db down");
            when(service.sweepOnce(eq(Duration.ofMinutes(5)), eq(1000))).thenReturn(Future.failedFuture(boom));

            Future<Void> result = impl(service, 1000, Duration.ofMinutes(5)).reconcile();

            assertTrue(result.failed(), "reconcile() should propagate service failure");
            assertSame(boom, result.cause(), "underlying exception must propagate unchanged");
        }
    }

    @Nested
    @DisplayName("config defaults")
    class Defaults {

        @Test
        @DisplayName("default config: 1000-row batch, 5-minute stale threshold")
        void defaultsAreConservative() {
            WorkflowBranchRecoveryConfig defaults = WorkflowBranchRecoveryConfig.defaults();
            assertEquals(1000, defaults.batchSize());
            assertEquals(Duration.ofMinutes(5), defaults.staleThreshold());
        }
    }
}
