// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleDeployment;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ServiceDeploymentManager}.
 *
 * <p>Uses Mockito mocks for {@link VerticleDeployer} and {@link ServiceSupervisor} to isolate
 * deployment and supervision interactions. A real {@link Vertx} instance (from the VertxExtension)
 * is used to enable codec registration and event bus operations.
 *
 * <p>Covers: successful deploy, partial-failure rollback, undeploy with supervisor deregistration,
 * and undeploy failure with supervisor still called. Stale-tracking eviction tests have moved to
 * {@code VerticleSupervisorTest} in the {@code deploy} module since that logic now lives there.
 */
@ExtendWith(VertxExtension.class)
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceDeploymentManager")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ServiceDeploymentManagerTest {

    // --- Contract Fixtures ---

    /** Single-method service contract for basic deployment tests. */
    @ServiceContract(value = "test-svc", namespace = "test")
    interface TestContract {
        /**
         * Dummy method.
         *
         * @param name the name argument
         * @return a future greeting
         */
        @ServiceOperation("hello")
        Future<String> hello(String name);
    }

    /** Implementation of {@link TestContract}. */
    static class TestContractImpl implements TestContract {
        @Override
        public Future<String> hello(String name) {
            return Future.succeededFuture("Hello " + name);
        }
    }

    /** Second service contract used for multi-service tests. */
    @ServiceContract(value = "other-svc", namespace = "test")
    interface OtherContract {
        /**
         * Dummy method.
         *
         * @return a future pong string
         */
        @ServiceOperation("ping")
        Future<String> ping();
    }

    /** Implementation of {@link OtherContract}. */
    static class OtherContractImpl implements OtherContract {
        @Override
        public Future<String> ping() {
            return Future.succeededFuture("pong");
        }
    }

    // --- Mocks ---

    @Mock
    VerticleDeployer deployer;

    @Mock
    ServiceSupervisor supervisor;

    @Mock
    ServiceExceptionMapper exceptionMapper;

    // --- Helpers ---

    /**
     * Builds the expected deployment name for {@link TestContract}.
     *
     * @return the deterministic deployment name string
     */
    private static String testContractDeploymentName() {
        return "dispatch-service:test/test-svc#" + TestContract.class.getName();
    }

    /**
     * Builds the expected deployment name for {@link OtherContract}.
     *
     * @return the deterministic deployment name string
     */
    private static String otherContractDeploymentName() {
        return "dispatch-service:test/other-svc#" + OtherContract.class.getName();
    }

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    /**
     * Creates a {@link ServiceDeploymentManager} with a registry containing only {@link TestContract}.
     *
     * @param vertx the Vert.x instance
     * @return the configured manager
     */
    private ServiceDeploymentManager managerWithOneService(Vertx vertx) {
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(new TestContractImpl()), new JsonObject(), configParser());
        return new ServiceDeploymentManager(vertx, deployer, registry, supervisor, exceptionMapper, Set.of(), Map.of());
    }

    /**
     * Creates a {@link ServiceDeploymentManager} with a registry containing both
     * {@link TestContract} and {@link OtherContract}.
     *
     * @param vertx the Vert.x instance
     * @return the configured manager
     */
    private ServiceDeploymentManager managerWithTwoServices(Vertx vertx) {
        ServiceContractRegistry registry = ServiceContractRegistry.build(
                Set.of(new TestContractImpl(), new OtherContractImpl()), new JsonObject(), configParser());
        return new ServiceDeploymentManager(vertx, deployer, registry, supervisor, exceptionMapper, Set.of(), Map.of());
    }

    // --- Tests ---

    /**
     * When the registry contains one service and the deployer succeeds, {@code deployAll()} must
     * call {@link VerticleDeployer#deploy} exactly once and then register the contract with the
     * supervisor via {@link ServiceSupervisor#watch} using the deployment name and deployment ID.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    @DisplayName("deployAll delegates to deployer and registers with supervisor")
    void deployAll_delegatesToDeployer(Vertx vertx, VertxTestContext ctx) throws Throwable {
        when(deployer.deploy(any(VerticleDeployment.class))).thenReturn(Future.succeededFuture("dep-1"));

        ServiceDeploymentManager manager = managerWithOneService(vertx);

        manager.deployAll().onComplete(ctx.succeeding(v -> {
            ctx.verify(() -> {
                verify(deployer, times(1)).deploy(any(VerticleDeployment.class));
                verify(supervisor, times(1))
                        .watch(
                                eq(TestContract.class),
                                eq("test"),
                                eq("test-svc"),
                                eq(testContractDeploymentName()),
                                eq("dep-1"),
                                any(Runnable.class));
            });
            ctx.completeNow();
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * When two services are in the registry and the second deploy fails, {@code deployAll()} must
     * roll back the successfully deployed first service by calling
     * {@link VerticleDeployer#undeploy} once for it.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    @DisplayName("deployAll rolls back on partial failure")
    void deployAll_partialFailure_rollsBack(Vertx vertx, VertxTestContext ctx) throws Throwable {
        String testContractName = testContractDeploymentName();
        String otherContractName = otherContractDeploymentName();

        // First call succeeds, second call fails regardless of argument order.
        // Null-guard in argThat is required: Mockito may pass null during matcher evaluation.
        when(deployer.deploy(argThat(d -> d != null && d.name().equals(testContractName))))
                .thenReturn(Future.succeededFuture("dep-1"));
        when(deployer.deploy(argThat(d -> d != null && d.name().equals(otherContractName))))
                .thenReturn(Future.failedFuture(new RuntimeException("deploy failed")));

        when(deployer.undeploy(anyString())).thenReturn(Future.succeededFuture());

        ServiceDeploymentManager manager = managerWithTwoServices(vertx);

        manager.deployAll().onComplete(ctx.failing(cause -> {
            ctx.verify(() -> {
                // The successful deployment (testContractName) must be rolled back
                verify(deployer, times(1)).undeploy(testContractName);
            });
            ctx.completeNow();
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * After a successful {@code deployAll()}, calling {@code undeployAll()} must deregister from
     * the supervisor before undeploying, once per deployed service.
     *
     * <p>Uses a Mockito {@link InOrder} verifier to prove that {@link ServiceSupervisor#deregister}
     * is called <em>before</em> {@link VerticleDeployer#undeploy} — the ordering guarantee that
     * prevents the supervisor from triggering a spurious restart of a verticle being torn down.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    @DisplayName("undeployAll deregisters from supervisor BEFORE undeploying (no spurious restart)")
    void undeployAll_deregistersFromSupervisor(Vertx vertx, VertxTestContext ctx) throws Throwable {
        when(deployer.deploy(any(VerticleDeployment.class))).thenReturn(Future.succeededFuture("dep-1"));
        when(deployer.undeploy(anyString())).thenReturn(Future.succeededFuture());

        ServiceDeploymentManager manager = managerWithOneService(vertx);

        manager.deployAll().compose(v -> manager.undeployAll()).onComplete(ctx.succeeding(v -> {
            ctx.verify(() -> {
                // The ordering guarantee: deregister must precede undeploy so the supervisor
                // cannot restart a verticle that is in the process of being torn down (AC-14).
                InOrder inOrder = inOrder(supervisor, deployer);
                inOrder.verify(supervisor).deregister(TestContract.class);
                inOrder.verify(deployer).undeploy(testContractDeploymentName());
            });
            ctx.completeNow();
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * When {@code deployer.undeploy()} fails during {@code undeployAll()}, the supervisor must
     * still have been called to deregister the contract (deregistration happens before the
     * undeploy attempt).
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    @DisplayName("undeployAll still calls supervisor.deregister even when undeploy fails")
    void undeployAll_retainsTrackingOnFailure(Vertx vertx, VertxTestContext ctx) throws Throwable {
        when(deployer.deploy(any(VerticleDeployment.class))).thenReturn(Future.succeededFuture("dep-1"));
        when(deployer.undeploy(anyString())).thenReturn(Future.failedFuture(new RuntimeException("undeploy failed")));

        ServiceDeploymentManager manager = managerWithOneService(vertx);

        // After deploy completes, trigger undeployAll and verify deregister was called regardless
        manager.deployAll().onComplete(ctx.succeeding(v -> {
            manager.undeployAll().onComplete(ar -> {
                ctx.verify(() -> verify(supervisor, times(1)).deregister(TestContract.class));
                ctx.completeNow();
            });
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }
}
