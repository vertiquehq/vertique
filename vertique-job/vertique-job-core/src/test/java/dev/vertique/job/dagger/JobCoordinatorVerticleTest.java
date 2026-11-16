// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.dagger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.job.JobCoordinator;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
@DisplayName("JobCoordinatorVerticle")
class JobCoordinatorVerticleTest {

    @Test
    @DisplayName("the coordinator deploys in SERVICES below the pollers' and cron scheduler's priority 100")
    void deploymentRunsBeforeClaimersAndStopsAfterThem() {
        VerticleDeployment deployment = JobCoordinatorVerticle.deployment(mock(JobCoordinator.class));

        assertEquals("job-coordinator", deployment.name());
        assertEquals(LifecyclePhase.SERVICES, deployment.phase());
        assertTrue(deployment.priority() < 100, "must deploy before and undeploy after the claimers at 100");
        assertInstanceOf(JobCoordinatorVerticle.class, deployment.supplier().get());
    }

    @Test
    @DisplayName("deploying starts the coordinator and undeploying stops it")
    void deployStartsAndUndeployStops(Vertx vertx, VertxTestContext ctx) {
        JobCoordinator coordinator = mock(JobCoordinator.class);
        when(coordinator.start()).thenReturn(Future.succeededFuture());
        when(coordinator.stop()).thenReturn(Future.succeededFuture());

        vertx.deployVerticle(new JobCoordinatorVerticle(coordinator))
                .compose(id -> {
                    ctx.verify(() -> {
                        verify(coordinator).start();
                        verify(coordinator, never()).stop();
                    });
                    return vertx.undeploy(id);
                })
                .onSuccess(v -> ctx.verify(() -> {
                    verify(coordinator).stop();
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
