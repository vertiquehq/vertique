// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.deploy;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link VerticleDeploymentManager}.
 *
 * <p>Verifies phase orchestration including priority ordering, parallel group execution, invocation-scoped
 * rollback on failure, cross-phase isolation, and rollback safety when re-running deployments on a
 * shared {@link VerticleDeployer} that already has prior healthy deployments.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class VerticleDeploymentManagerTest {

    // --- deployAll tests ---

    @Test
    void deployAll_emptySet_succeeds(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(deployer, Set.of());

        manager.deployAll().onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    void deployAll_singleVerticle_deploysAndTracksId(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(
                deployer, Set.of(VerticleDeployment.of("test", NoOpVerticle::new, LifecyclePhase.INFRA)));

        manager.deployAll().onComplete(ctx.succeeding(v -> {
            assertNotNull(deployer.deploymentId("test"));
            ctx.completeNow();
        }));
    }

    @Test
    void deployAll_priorityOrdering_deploysLowerFirst(Vertx vertx, VertxTestContext ctx) {
        List<String> startOrder = new CopyOnWriteArrayList<>();
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(
                deployer,
                Set.of(
                        VerticleDeployment.of(
                                "first", () -> new OrderTrackingVerticle(startOrder, "first"), LifecyclePhase.INFRA, 0),
                        VerticleDeployment.of(
                                "second",
                                () -> new OrderTrackingVerticle(startOrder, "second"),
                                LifecyclePhase.INFRA,
                                100)));

        manager.deployAll().onComplete(ctx.succeeding(v -> {
            assertEquals(List.of("first", "second"), startOrder);
            ctx.completeNow();
        }));
    }

    @Test
    void deployAll_samePriority_deploysBoth(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(
                deployer,
                Set.of(
                        VerticleDeployment.of("a", NoOpVerticle::new, LifecyclePhase.INFRA, 0),
                        VerticleDeployment.of("b", NoOpVerticle::new, LifecyclePhase.INFRA, 0)));

        manager.deployAll().onComplete(ctx.succeeding(v -> {
            assertNotNull(deployer.deploymentId("a"));
            assertNotNull(deployer.deploymentId("b"));
            ctx.completeNow();
        }));
    }

    @Test
    void deployAll_multiInstance_callsSupplierMultipleTimes(Vertx vertx, VertxTestContext ctx) {
        AtomicInteger supplierCalls = new AtomicInteger();
        VerticleDeployment deployment = new VerticleDeployment(
                "multi",
                () -> {
                    supplierCalls.incrementAndGet();
                    return new NoOpVerticle();
                },
                new DeploymentOptions().setInstances(3),
                LifecyclePhase.INFRA,
                0);
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(deployer, Set.of(deployment));

        manager.deployAll().onComplete(ctx.succeeding(v -> {
            assertEquals(3, supplierCalls.get());
            assertNotNull(deployer.deploymentId("multi"));
            ctx.completeNow();
        }));
    }

    @Test
    void deployAll_passesConfigFromOptions(Vertx vertx, VertxTestContext ctx) {
        JsonObject expectedConfig = new JsonObject().put("key", "value");
        VerticleDeployment deployment = new VerticleDeployment(
                "configured",
                ConfigCapturingVerticle::new,
                new DeploymentOptions().setConfig(expectedConfig),
                LifecyclePhase.INFRA,
                0);
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(deployer, Set.of(deployment));

        manager.deployAll().onComplete(ctx.succeeding(v -> {
            assertNotNull(deployer.deploymentId("configured"));
            ctx.completeNow();
        }));
    }

    @Test
    void deployAll_partialFailure_rollsBack(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(
                deployer,
                Set.of(
                        VerticleDeployment.of("good", NoOpVerticle::new, LifecyclePhase.INFRA, 0),
                        VerticleDeployment.of("bad", FailingVerticle::new, LifecyclePhase.INFRA, 100)));

        manager.deployAll().onComplete(ctx.failing(cause -> {
            assertNull(deployer.deploymentId("good"), "Successfully deployed verticle should be rolled back");
            assertNull(deployer.deploymentId("bad"));
            ctx.completeNow();
        }));
    }

    @Test
    void deployAll_ordersByPhaseThenPriority(Vertx vertx, VertxTestContext ctx) {
        List<String> startOrder = new CopyOnWriteArrayList<>();
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(
                deployer,
                Set.of(
                        VerticleDeployment.of(
                                "edge", () -> new OrderTrackingVerticle(startOrder, "edge"), LifecyclePhase.EDGE),
                        VerticleDeployment.of(
                                "infra", () -> new OrderTrackingVerticle(startOrder, "infra"), LifecyclePhase.INFRA)));

        manager.deployAll().onComplete(ctx.succeeding(v -> {
            assertEquals(List.of("infra", "edge"), startOrder, "INFRA should deploy before EDGE");
            ctx.completeNow();
        }));
    }

    // --- deployPhase tests ---

    @Test
    void deployPhase_emptyPhase_succeeds(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        // Manager only has INFRA; requesting EDGE should succeed immediately
        VerticleDeploymentManager manager = new VerticleDeploymentManager(
                deployer, Set.of(VerticleDeployment.of("infra", NoOpVerticle::new, LifecyclePhase.INFRA)));

        manager.deployPhase(LifecyclePhase.EDGE).onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    void deployPhase_singleVerticle_deploys(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(
                deployer, Set.of(VerticleDeployment.of("infra", NoOpVerticle::new, LifecyclePhase.INFRA)));

        manager.deployPhase(LifecyclePhase.INFRA).onComplete(ctx.succeeding(v -> {
            assertNotNull(deployer.deploymentId("infra"));
            ctx.completeNow();
        }));
    }

    @Test
    void deployPhase_multiItem_deploysAll(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(
                deployer,
                Set.of(
                        VerticleDeployment.of("a", NoOpVerticle::new, LifecyclePhase.EDGE),
                        VerticleDeployment.of("b", NoOpVerticle::new, LifecyclePhase.EDGE)));

        manager.deployPhase(LifecyclePhase.EDGE).onComplete(ctx.succeeding(v -> {
            assertNotNull(deployer.deploymentId("a"));
            assertNotNull(deployer.deploymentId("b"));
            ctx.completeNow();
        }));
    }

    @Test
    void deployPhase_priorityWithinPhase_deploysLowerFirst(Vertx vertx, VertxTestContext ctx) {
        List<String> startOrder = new CopyOnWriteArrayList<>();
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(
                deployer,
                Set.of(
                        VerticleDeployment.of(
                                "first", () -> new OrderTrackingVerticle(startOrder, "first"), LifecyclePhase.INFRA, 0),
                        VerticleDeployment.of(
                                "second",
                                () -> new OrderTrackingVerticle(startOrder, "second"),
                                LifecyclePhase.INFRA,
                                100)));

        manager.deployPhase(LifecyclePhase.INFRA).onComplete(ctx.succeeding(v -> {
            assertEquals(List.of("first", "second"), startOrder);
            ctx.completeNow();
        }));
    }

    @Test
    void deployPhase_partialFailure_rollsBackPhase(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(
                deployer,
                Set.of(
                        VerticleDeployment.of("good", NoOpVerticle::new, LifecyclePhase.EDGE, 0),
                        VerticleDeployment.of("bad", FailingVerticle::new, LifecyclePhase.EDGE, 100)));

        manager.deployPhase(LifecyclePhase.EDGE).onComplete(ctx.failing(cause -> {
            assertNull(deployer.deploymentId("good"), "Successfully deployed verticle should be rolled back");
            assertNull(deployer.deploymentId("bad"));
            ctx.completeNow();
        }));
    }

    @Test
    void deployPhase_lateSuccessAfterFailure_noLeaks(Vertx vertx, VertxTestContext ctx) {
        // Three verticles at same priority — one fails.
        // Future.join ensures all settle before rollback, so no leaked deployments.
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleDeploymentManager manager = new VerticleDeploymentManager(
                deployer,
                Set.of(
                        VerticleDeployment.of("a", NoOpVerticle::new, LifecyclePhase.EDGE),
                        VerticleDeployment.of("bad", FailingVerticle::new, LifecyclePhase.EDGE),
                        VerticleDeployment.of("c", NoOpVerticle::new, LifecyclePhase.EDGE)));

        manager.deployPhase(LifecyclePhase.EDGE).onComplete(ctx.failing(cause -> {
            assertNull(deployer.deploymentId("a"), "Successful deployment a should be rolled back");
            assertNull(deployer.deploymentId("bad"), "Failed deployment should not be tracked");
            assertNull(deployer.deploymentId("c"), "Successful deployment c should be rolled back");
            // Verify no leaked Vert.x deployments
            assertTrue(vertx.deploymentIDs().isEmpty(), "No leaked Vert.x deployments");
            ctx.completeNow();
        }));
    }

    @Test
    void deployPhase_doesNotAffectOtherPhases(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        // manager1 deploys only INFRA
        VerticleDeploymentManager manager1 = new VerticleDeploymentManager(
                deployer, Set.of(VerticleDeployment.of("infra", NoOpVerticle::new, LifecyclePhase.INFRA)));
        // manager2 deploys only EDGE (fails)
        VerticleDeploymentManager manager2 = new VerticleDeploymentManager(
                deployer, Set.of(VerticleDeployment.of("bad-edge", FailingVerticle::new, LifecyclePhase.EDGE)));

        // Deploy INFRA first (succeeds), then deploy EDGE via a separate manager (fails)
        manager1.deployPhase(LifecyclePhase.INFRA)
                .compose(v -> {
                    assertNotNull(deployer.deploymentId("infra"));
                    return manager2.deployPhase(LifecyclePhase.EDGE);
                })
                .onComplete(ctx.failing(cause -> {
                    // INFRA should still be deployed — manager2 rollback must not touch it
                    assertNotNull(deployer.deploymentId("infra"), "INFRA should NOT be rolled back");
                    assertNull(deployer.deploymentId("bad-edge"));
                    ctx.completeNow();
                }));
    }

    // --- deployPhase non-verticle phase rejection tests (AC-3) ---

    @Nested
    @DisplayName("deployPhase rejects non-verticle phases with IllegalArgumentException")
    class DeployPhaseNonVerticleRejection {

        @Test
        @DisplayName("deployPhase(CONFIGURE) throws IllegalArgumentException")
        void deployPhase_configure_throwsIllegalArgumentException(Vertx vertx) {
            VerticleDeployer deployer = new VerticleDeployer(vertx);
            VerticleDeploymentManager manager = new VerticleDeploymentManager(deployer, Set.of());

            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> manager.deployPhase(LifecyclePhase.CONFIGURE));
            assertTrue(
                    ex.getMessage().contains("CONFIGURE"),
                    "message must identify the rejected phase, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("deployPhase(VALIDATE) throws IllegalArgumentException")
        void deployPhase_validate_throwsIllegalArgumentException(Vertx vertx) {
            VerticleDeployer deployer = new VerticleDeployer(vertx);
            VerticleDeploymentManager manager = new VerticleDeploymentManager(deployer, Set.of());

            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> manager.deployPhase(LifecyclePhase.VALIDATE));
            assertTrue(
                    ex.getMessage().contains("VALIDATE"),
                    "message must identify the rejected phase, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("deployPhase(MIGRATE) throws IllegalArgumentException")
        void deployPhase_migrate_throwsIllegalArgumentException(Vertx vertx) {
            VerticleDeployer deployer = new VerticleDeployer(vertx);
            VerticleDeploymentManager manager = new VerticleDeploymentManager(deployer, Set.of());

            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> manager.deployPhase(LifecyclePhase.MIGRATE));
            assertTrue(
                    ex.getMessage().contains("MIGRATE"),
                    "message must identify the rejected phase, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("deployPhase(AFTER_START) throws IllegalArgumentException")
        void deployPhase_afterStart_throwsIllegalArgumentException(Vertx vertx) {
            VerticleDeployer deployer = new VerticleDeployer(vertx);
            VerticleDeploymentManager manager = new VerticleDeploymentManager(deployer, Set.of());

            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> manager.deployPhase(LifecyclePhase.AFTER_START));
            assertTrue(
                    ex.getMessage().contains("AFTER_START"),
                    "message must identify the rejected phase, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("deployPhase(INFRA) succeeds — a verticle phase is not rejected")
        void deployPhase_infra_doesNotThrow(Vertx vertx, VertxTestContext ctx) {
            VerticleDeployer deployer = new VerticleDeployer(vertx);
            VerticleDeploymentManager manager = new VerticleDeploymentManager(deployer, Set.of());

            // An empty manager still returns a succeeded future — no IAE thrown.
            assertDoesNotThrow(
                    () -> manager.deployPhase(LifecyclePhase.INFRA).onComplete(ctx.succeeding(v -> ctx.completeNow())));
        }
    }

    // --- Re-run rollback safety tests ---

    @Test
    void deployPhase_rerun_rollbackDoesNotTouchPriorDeployments(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        // First run: deploy infra-ok via manager1 — succeeds
        VerticleDeploymentManager manager1 = new VerticleDeploymentManager(
                deployer, Set.of(VerticleDeployment.of("infra-ok", NoOpVerticle::new, LifecyclePhase.INFRA)));

        // Second run: deploy infra-ok (already in deployer → rejected) + infra-bad (fails) via manager2
        VerticleDeploymentManager manager2 = new VerticleDeploymentManager(
                deployer,
                Set.of(
                        VerticleDeployment.of("infra-ok", NoOpVerticle::new, LifecyclePhase.INFRA),
                        VerticleDeployment.of("infra-bad", FailingVerticle::new, LifecyclePhase.INFRA)));

        manager1.deployPhase(LifecyclePhase.INFRA)
                .compose(v -> {
                    assertNotNull(deployer.deploymentId("infra-ok"), "Prior deployment should be tracked");
                    // manager2 fails: infra-ok is rejected as duplicate, infra-bad fails on start
                    return manager2.deployPhase(LifecyclePhase.INFRA);
                })
                .onComplete(ctx.failing(cause -> {
                    // manager2 rollback must NOT undeploy infra-ok — it was not started by this invocation
                    assertNotNull(
                            deployer.deploymentId("infra-ok"),
                            "Prior deployment must not be rolled back by a failed re-run");
                    assertNull(deployer.deploymentId("infra-bad"), "Failed deployment must not be tracked");
                    ctx.completeNow();
                }));
    }

    @Test
    void deployAll_rerun_rollbackDoesNotTouchPriorDeployments(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        // First run: deploy infra-ok + edge-ok via manager1 — both succeed
        VerticleDeploymentManager manager1 = new VerticleDeploymentManager(
                deployer,
                Set.of(
                        VerticleDeployment.of("infra-ok", NoOpVerticle::new, LifecyclePhase.INFRA),
                        VerticleDeployment.of("edge-ok", NoOpVerticle::new, LifecyclePhase.EDGE)));

        // Second run: includes the already-deployed pair plus a new edge-bad — will fail
        VerticleDeploymentManager manager2 = new VerticleDeploymentManager(
                deployer,
                Set.of(
                        VerticleDeployment.of("infra-ok", NoOpVerticle::new, LifecyclePhase.INFRA),
                        VerticleDeployment.of("edge-ok", NoOpVerticle::new, LifecyclePhase.EDGE),
                        VerticleDeployment.of("edge-bad", FailingVerticle::new, LifecyclePhase.EDGE)));

        manager1.deployAll()
                .compose(v -> {
                    assertNotNull(deployer.deploymentId("infra-ok"), "infra-ok should be deployed");
                    assertNotNull(deployer.deploymentId("edge-ok"), "edge-ok should be deployed");
                    // manager2 fails: infra-ok and edge-ok rejected as duplicates, edge-bad fails on start
                    return manager2.deployAll();
                })
                .onComplete(ctx.failing(cause -> {
                    // Rollback from manager2 must NOT touch infra-ok or edge-ok
                    assertNotNull(
                            deployer.deploymentId("infra-ok"), "infra-ok must not be rolled back by a failed re-run");
                    assertNotNull(
                            deployer.deploymentId("edge-ok"), "edge-ok must not be rolled back by a failed re-run");
                    assertNull(deployer.deploymentId("edge-bad"), "Failed deployment must not be tracked");
                    ctx.completeNow();
                }));
    }

    // --- Test verticles ---

    /** A no-operation verticle used to verify successful deployment and tracking. */
    static class NoOpVerticle extends AbstractVerticle {}

    /**
     * A verticle that records its name in a shared list on startup, enabling start-order assertions.
     */
    static class OrderTrackingVerticle extends AbstractVerticle {
        private final List<String> order;
        private final String name;

        /**
         * Creates a new tracking verticle.
         *
         * @param order shared list to append this verticle's name to on start
         * @param name the name to append
         */
        OrderTrackingVerticle(List<String> order, String name) {
            this.order = order;
            this.name = name;
        }

        @Override
        public void start() {
            order.add(name);
        }
    }

    /**
     * A verticle that fails at start unless the Vert.x deployment config contains at least one entry.
     * Used to verify that {@link DeploymentOptions#setConfig(JsonObject)} is passed through correctly.
     */
    static class ConfigCapturingVerticle extends AbstractVerticle {
        @Override
        public void start(Promise<Void> startPromise) {
            if (config() == null || config().isEmpty()) {
                startPromise.fail("Expected non-empty config");
            } else {
                startPromise.complete();
            }
        }
    }

    /** A verticle that always fails at startup, used to trigger rollback scenarios. */
    static class FailingVerticle extends AbstractVerticle {
        @Override
        public void start(Promise<Void> startPromise) {
            startPromise.fail("Intentional failure");
        }
    }
}
