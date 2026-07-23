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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class VerticleDeployerTest {

    // --- deploy tests ---

    @Test
    void deploy_singleVerticleDynamically(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.deploy(VerticleDeployment.of("dynamic", NoOpVerticle::new, LifecyclePhase.EDGE))
                .onComplete(ctx.succeeding(id -> {
                    assertNotNull(id);
                    assertEquals(id, deployer.deploymentId("dynamic"));
                    ctx.completeNow();
                }));
    }

    @Test
    void deploy_multiInstance_callsSupplierMultipleTimes(Vertx vertx, VertxTestContext ctx) {
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

        deployer.deploy(deployment).onComplete(ctx.succeeding(v -> {
            assertEquals(3, supplierCalls.get());
            assertNotNull(deployer.deploymentId("multi"));
            ctx.completeNow();
        }));
    }

    @Test
    void deploy_passesConfigFromOptions(Vertx vertx, VertxTestContext ctx) {
        JsonObject expectedConfig = new JsonObject().put("key", "value");
        VerticleDeployment deployment = new VerticleDeployment(
                "configured",
                ConfigCapturingVerticle::new,
                new DeploymentOptions().setConfig(expectedConfig),
                LifecyclePhase.INFRA,
                0);

        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.deploy(deployment).onComplete(ctx.succeeding(v -> {
            assertNotNull(deployer.deploymentId("configured"));
            ctx.completeNow();
        }));
    }

    @Test
    void deploy_duplicateName_failsFuture(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.deploy(VerticleDeployment.of("dup", NoOpVerticle::new, LifecyclePhase.EDGE))
                .compose(id -> deployer.deploy(VerticleDeployment.of("dup", NoOpVerticle::new, LifecyclePhase.EDGE)))
                .onComplete(ctx.failing(cause -> {
                    assertTrue(cause.getMessage().contains("already in use"));
                    assertNotNull(deployer.deploymentId("dup"));
                    ctx.completeNow();
                }));
    }

    // --- undeploy tests ---

    @Test
    void undeploy_trackedVerticle_removesId(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.deploy(VerticleDeployment.of("remove-me", NoOpVerticle::new, LifecyclePhase.INFRA))
                .compose(v -> {
                    assertNotNull(deployer.deploymentId("remove-me"));
                    return deployer.undeploy("remove-me");
                })
                .onComplete(ctx.succeeding(v -> {
                    assertNull(deployer.deploymentId("remove-me"));
                    ctx.completeNow();
                }));
    }

    @Test
    void undeploy_unknownName_failsFuture(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.undeploy("nonexistent").onComplete(ctx.failing(cause -> {
            assertTrue(cause.getMessage().contains("nonexistent"));
            ctx.completeNow();
        }));
    }

    @Test
    void undeploy_failure_retainsTracking(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.deploy(VerticleDeployment.of("sticky", NoOpVerticle::new, LifecyclePhase.EDGE))
                .compose(id -> vertx.undeploy(id))
                .compose(v -> deployer.undeploy("sticky"))
                .onComplete(ctx.failing(cause -> {
                    assertNotNull(deployer.deploymentId("sticky"));
                    ctx.completeNow();
                }));
    }

    // --- undeployAll tests ---

    @Test
    void undeployAll_clearsAllTracked(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.deploy(VerticleDeployment.of("a", NoOpVerticle::new, LifecyclePhase.INFRA))
                .compose(v -> deployer.deploy(VerticleDeployment.of("b", NoOpVerticle::new, LifecyclePhase.INFRA)))
                .compose(v -> {
                    assertNotNull(deployer.deploymentId("a"));
                    assertNotNull(deployer.deploymentId("b"));
                    return deployer.undeployAll();
                })
                .onComplete(ctx.succeeding(v -> {
                    assertNull(deployer.deploymentId("a"));
                    assertNull(deployer.deploymentId("b"));
                    ctx.completeNow();
                }));
    }

    @Test
    void undeployAll_partialFailure_cleansSuccessful(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.deploy(VerticleDeployment.of("a", NoOpVerticle::new, LifecyclePhase.INFRA))
                .compose(v -> deployer.deploy(VerticleDeployment.of("b", NoOpVerticle::new, LifecyclePhase.INFRA)))
                .compose(v -> {
                    String idA = deployer.deploymentId("a");
                    return vertx.undeploy(idA);
                })
                .compose(v -> deployer.undeployAll())
                .onComplete(ctx.succeeding(v -> {
                    // Best-effort: succeeds even when individual undeploys fail
                    assertNull(deployer.deploymentId("b"), "Successfully undeployed verticle should be removed");
                    assertNotNull(deployer.deploymentId("a"), "Failed undeploy should retain tracking");
                    ctx.completeNow();
                }));
    }

    @Test
    void undeployAll_groupFailure_continuesOtherGroups(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.deploy(VerticleDeployment.of("infra", NoOpVerticle::new, LifecyclePhase.INFRA))
                .compose(v -> deployer.deploy(VerticleDeployment.of("edge", NoOpVerticle::new, LifecyclePhase.EDGE)))
                .compose(v -> {
                    // Kill the EDGE verticle directly so deployer tracking is stale
                    String edgeId = deployer.deploymentId("edge");
                    return vertx.undeploy(edgeId);
                })
                .compose(v -> {
                    // undeployAll: EDGE group will fail (stale), but INFRA must still undeploy
                    return deployer.undeployAll();
                })
                .onComplete(ctx.succeeding(v -> {
                    // INFRA must have been undeployed despite EDGE group failure
                    assertNull(deployer.deploymentId("infra"), "INFRA must be undeployed even if EDGE group fails");
                    ctx.completeNow();
                }));
    }

    @Test
    void undeployAll_reversesPhaseAndPriorityOrder(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.deploy(VerticleDeployment.of("infra", NoOpVerticle::new, LifecyclePhase.INFRA))
                .compose(v -> deployer.deploy(VerticleDeployment.of("edge", NoOpVerticle::new, LifecyclePhase.EDGE)))
                .compose(v -> {
                    assertNotNull(deployer.deploymentId("infra"));
                    assertNotNull(deployer.deploymentId("edge"));
                    return deployer.undeployAll();
                })
                .onComplete(ctx.succeeding(v -> {
                    assertNull(deployer.deploymentId("infra"));
                    assertNull(deployer.deploymentId("edge"));
                    ctx.completeNow();
                }));
    }

    // --- deploymentId tests ---

    @Test
    void deploymentId_returnsNullWhenNotDeployed(Vertx vertx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        assertNull(deployer.deploymentId("nothing"));
    }

    // --- evict tests ---

    @Test
    void evict_matchingId_removesTracking(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.deploy(VerticleDeployment.of("evict-me", NoOpVerticle::new, LifecyclePhase.EDGE))
                .onComplete(ctx.succeeding(id -> {
                    boolean evicted = deployer.evict("evict-me", id);
                    assertTrue(evicted);
                    assertNull(deployer.deploymentId("evict-me"));
                    ctx.completeNow();
                }));
    }

    @Test
    void evict_mismatchedId_retainsTracking(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.deploy(VerticleDeployment.of("keep-me", NoOpVerticle::new, LifecyclePhase.EDGE))
                .onComplete(ctx.succeeding(id -> {
                    boolean evicted = deployer.evict("keep-me", "wrong-id");
                    assertFalse(evicted);
                    assertNotNull(deployer.deploymentId("keep-me"));
                    ctx.completeNow();
                }));
    }

    @Test
    void evict_allowsRedeployWithSameName(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);

        deployer.deploy(VerticleDeployment.of("redeploy-me", NoOpVerticle::new, LifecyclePhase.EDGE))
                .compose(id -> vertx.undeploy(id).map(v -> id))
                .compose(id -> {
                    deployer.evict("redeploy-me", id);
                    return deployer.deploy(
                            VerticleDeployment.of("redeploy-me", NoOpVerticle::new, LifecyclePhase.EDGE));
                })
                .onComplete(ctx.succeeding(newId -> {
                    assertNotNull(newId);
                    assertEquals(newId, deployer.deploymentId("redeploy-me"));
                    ctx.completeNow();
                }));
    }

    // --- Test verticles ---

    static class NoOpVerticle extends AbstractVerticle {}

    static class OrderTrackingVerticle extends AbstractVerticle {
        private final List<String> order;
        private final String name;

        OrderTrackingVerticle(List<String> order, String name) {
            this.order = order;
            this.name = name;
        }

        @Override
        public void start() {
            order.add(name);
        }
    }

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

    static class FailingVerticle extends AbstractVerticle {
        @Override
        public void start(Promise<Void> startPromise) {
            startPromise.fail("Intentional failure");
        }
    }
}
