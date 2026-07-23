// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeploymentManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * Integration test exercising the paired {@link ServiceDeploymentStartupStep} /
 * {@link ServiceDeploymentShutdownStep} through the host-neutral lifecycle runner
 * ({@link VertiqueApplicationBootstrap}).
 *
 * <p>The runner is driven against a fake {@link VertiqueApplicationComponent} whose startup steps
 * include the <em>real</em> {@link ServiceDeploymentStartupStep} (over a mock
 * {@link ServiceDeploymentManager}) and whose verticle deployment is a mock
 * {@link VerticleDeploymentManager}. This proves the wiring end-to-end without standing up real
 * service verticles.
 *
 * <p>Coverage:
 * <ul>
 *   <li><b>AC-11</b> — {@code serviceDeploymentManager.deployAll()} runs after the INFRA verticle
 *       deploy and before the {@code SERVICES}-phase verticle deploy (the low-priority SERVICES
 *       startup step runs before that phase's verticles). Asserted with Mockito {@link InOrder}.</li>
 *   <li><b>AC-14</b> — when a later (EDGE) startup step fails, teardown invokes
 *       {@code serviceDeploymentManager.undeployAll()} (the owner-managed, deregister-first teardown)
 *       as part of the reverse shutdown-step walk, <em>before</em> the runner's generic
 *       {@code verticleDeploymentManager.undeployAll()}; the original cause propagates. Asserted
 *       with {@link InOrder}.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ServiceDeploymentLifecycleStepsIT {

    @Test
    @DisplayName("AC-11: service deploy runs after INFRA verticles and before SERVICES verticles")
    void serviceDeploy_runsAfterInfra_beforeServicesVerticles(Vertx vertx, VertxTestContext ctx) {
        ServiceDeploymentManager serviceManager = mock(ServiceDeploymentManager.class);
        when(serviceManager.deployAll()).thenReturn(Future.succeededFuture());

        VerticleDeploymentManager verticleManager = mock(VerticleDeploymentManager.class);
        when(verticleManager.deployPhase(Mockito.any())).thenReturn(Future.succeededFuture());

        // Real SERVICES-phase startup step over the mock manager; no shutdown steps needed here.
        Set<ApplicationStartupStep> startup = orderedSet(new ServiceDeploymentStartupStep(serviceManager));
        FakeComponent component = new FakeComponent(startup, Set.of(), verticleManager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component)
                .onComplete(ctx.succeeding(handle -> ctx.verify(() -> {
                    // The SERVICES-phase startup step (service deploy) runs before that phase's
                    // verticle deploy, and after the prior phase's (INFRA) verticle deploy.
                    InOrder inOrder = Mockito.inOrder(verticleManager, serviceManager);
                    inOrder.verify(verticleManager).deployPhase(LifecyclePhase.INFRA);
                    inOrder.verify(serviceManager).deployAll();
                    inOrder.verify(verticleManager).deployPhase(LifecyclePhase.SERVICES);

                    // Sanity: deployAll happened exactly once, undeployAll never (clean start).
                    verify(serviceManager).deployAll();
                    verify(serviceManager, never()).undeployAll();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName(
            "AC-14: a later startup failure undeploys services (deregister-first) before generic verticle undeploy")
    void laterFailure_tearsDownServicesBeforeGenericVerticles(Vertx vertx, VertxTestContext ctx) {
        RuntimeException boom = new RuntimeException("edge boom");

        ServiceDeploymentManager serviceManager = mock(ServiceDeploymentManager.class);
        when(serviceManager.deployAll()).thenReturn(Future.succeededFuture());
        when(serviceManager.undeployAll()).thenReturn(Future.succeededFuture());

        VerticleDeploymentManager verticleManager = mock(VerticleDeploymentManager.class);
        when(verticleManager.deployPhase(Mockito.any())).thenReturn(Future.succeededFuture());
        when(verticleManager.undeployAll()).thenReturn(Future.succeededFuture());

        // Real paired steps over the mock manager, plus a forced EDGE failure after services deploy.
        Set<ApplicationStartupStep> startup = orderedSet(
                new ServiceDeploymentStartupStep(serviceManager), new FailingStartupStep(LifecyclePhase.EDGE, boom));
        Set<ApplicationShutdownStep> shutdown = orderedSet(new ServiceDeploymentShutdownStep(serviceManager));

        FakeComponent component = new FakeComponent(startup, shutdown, verticleManager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component)
                .onComplete(ctx.failing(cause -> ctx.verify(() -> {
                    assertSame(boom, cause, "the original startup cause propagates");

                    // Owner-managed teardown (service undeployAll, deregister-first) runs before the runner's
                    // generic verticle undeployAll.
                    InOrder inOrder = Mockito.inOrder(serviceManager, verticleManager);
                    inOrder.verify(serviceManager).undeployAll();
                    inOrder.verify(verticleManager).undeployAll();
                    ctx.completeNow();
                })));
    }

    // --- Test helpers ---

    /**
     * Wraps the given participants in an insertion-ordered {@link LinkedHashSet}; the runner sorts by
     * {@code LifecycleOrdered.comparator()}, so insertion order is not relied upon.
     *
     * @param items the participants
     * @param <T> the participant type
     * @return an insertion-ordered set of the items
     */
    @SafeVarargs
    private static <T> Set<T> orderedSet(T... items) {
        return new LinkedHashSet<>(List.of(items));
    }

    // --- Test doubles ---

    /** Fake passive component returning fixed step sets and a (mock) verticle deployment manager. */
    private record FakeComponent(
            Set<ApplicationStartupStep> startupSteps,
            Set<ApplicationShutdownStep> shutdownSteps,
            VerticleDeploymentManager verticleDeploymentManager)
            implements VertiqueApplicationComponent {}

    /** A startup step in a chosen phase that fails with a fixed cause, to drive failure teardown. */
    private record FailingStartupStep(LifecyclePhase phase, Throwable cause) implements ApplicationStartupStep {
        @Override
        public String orderKey() {
            return "failing-step";
        }

        @Override
        public Future<Void> start() {
            return Future.failedFuture(cause);
        }
    }
}
