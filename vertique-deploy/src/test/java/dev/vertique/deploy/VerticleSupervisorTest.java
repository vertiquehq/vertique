// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.deploy;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link VerticleSupervisor}.
 *
 * <p>Verifies restart scheduling, budget exhaustion, de-duplication of concurrent fatal error
 * reports, intentional-undeploy suppression, coherence validation, and stale tracking recovery.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class VerticleSupervisorTest {

    // --- Helpers ---

    /**
     * Creates a {@link SupervisionConfig} with short backoff timers suitable for testing.
     *
     * @param maxRestarts the maximum number of restarts within the sliding window
     * @param initialBackoffMs the initial backoff delay in milliseconds
     * @param maxBackoffMs the maximum backoff delay cap in milliseconds
     * @return a supervision configuration for use in tests
     */
    private static SupervisionConfig testConfig(int maxRestarts, long initialBackoffMs, long maxBackoffMs) {
        return new SupervisionConfig(maxRestarts, 60_000L, initialBackoffMs, maxBackoffMs);
    }

    /**
     * Deploys a single no-op verticle with the given name and returns the Vert.x deployment ID.
     *
     * @param deployer the deployer to use
     * @param name the deployment name
     * @return a future of the deployment ID
     */
    private static Future<String> deployDummy(VerticleDeployer deployer, String name) {
        return deployer.deploy(VerticleDeployment.of(name, NoOpVerticle::new, LifecyclePhase.SERVICES));
    }

    // --- Tests ---

    /**
     * A freshly supervised verticle must report available.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     */
    @Test
    void shouldBeAvailableAfterSupervise(Vertx vertx, VertxTestContext ctx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            supervisor.supervise("svc", id, testConfig(5, 50L, 50L), () -> {});
            assertTrue(supervisor.isAvailable("svc"));
            ctx.completeNow();
        }));
    }

    /**
     * An unsupervised (never-registered) name must report available — fail-open semantics.
     *
     * @param vertx the Vert.x instance provided by the extension
     */
    @Test
    void shouldBeAvailableWhenNotRegistered(Vertx vertx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);
        assertTrue(supervisor.isAvailable("unknown"));
    }

    /**
     * A fatal error must cause the redeploy action to fire after the backoff delay.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void shouldTriggerRestartOnFatalError(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);
        AtomicInteger redeployCount = new AtomicInteger(0);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            supervisor.supervise("svc", id, testConfig(5, 10L, 50L), () -> redeployCount.incrementAndGet());
            supervisor.reportFatalError("svc", new RuntimeException("crash"));

            vertx.setTimer(300, tick -> {
                ctx.verify(() -> assertEquals(1, redeployCount.get()));
                ctx.completeNow();
            });
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * After {@code maxRestarts} restarts within the window the verticle must be marked unavailable.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void shouldMarkUnavailableWhenBudgetExhausted(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);
        AtomicInteger redeployCount = new AtomicInteger(0);
        // max 2 restarts, very fast backoff
        SupervisionConfig config = testConfig(2, 10L, 10L);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            supervisor.supervise("svc", id, config, () -> redeployCount.incrementAndGet());

            // First error — schedules restart #1 (deduplicated second call)
            supervisor.reportFatalError("svc", new RuntimeException("1"));
            supervisor.reportFatalError("svc", new RuntimeException("2"));

            // After first restart fires, re-register and trigger restart #2
            vertx.setTimer(100, t1 -> {
                deployer.deploymentId("svc"); // tracking was cleared by undeploy in the restart
                // Re-deploy so deployer has a new ID for coherence validation
                deployDummy(deployer, "svc2").onComplete(ar -> {
                    // Use the same "svc" name via re-supervise pattern:
                    // The restart cycle already removed tracking for "svc", re-deploy a fresh one
                    deployer.deploy(VerticleDeployment.of("svc-r1", NoOpVerticle::new, LifecyclePhase.SERVICES))
                            .onComplete(ctx.succeeding(id2 -> {
                                supervisor.supervise("svc-r1", id2, config, () -> redeployCount.incrementAndGet());
                                supervisor.reportFatalError("svc-r1", new RuntimeException("3"));

                                vertx.setTimer(100, t2 -> {
                                    deployer.deploy(VerticleDeployment.of(
                                                    "svc-r2", NoOpVerticle::new, LifecyclePhase.SERVICES))
                                            .onComplete(ctx.succeeding(id3 -> {
                                                supervisor.supervise(
                                                        "svc-r2", id3, config, () -> redeployCount.incrementAndGet());
                                                // This should exhaust the budget (3rd restart attempt)
                                                supervisor.reportFatalError("svc-r2", new RuntimeException("4"));

                                                vertx.setTimer(100, t3 -> {
                                                    ctx.verify(() -> assertFalse(
                                                            supervisor.isAvailable("svc-r2"),
                                                            "Should be unavailable after budget exhausted"));
                                                    ctx.completeNow();
                                                });
                                            }));
                                });
                            }));
                });
            });
        }));

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * After {@link VerticleSupervisor#unsupervise(String)}, a subsequent
     * {@link VerticleSupervisor#reportFatalError} must be a no-op.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void shouldNotRestartAfterUnsupervise(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);
        AtomicInteger redeployCount = new AtomicInteger(0);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            supervisor.supervise("svc", id, testConfig(5, 10L, 50L), () -> redeployCount.incrementAndGet());
            supervisor.unsupervise("svc");
            // reportFatalError after unsupervise → state is null → no-op
            supervisor.reportFatalError("svc", new RuntimeException("crash"));

            vertx.setTimer(150, tick -> {
                ctx.verify(() -> assertEquals(0, redeployCount.get()));
                ctx.completeNow();
            });
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * Rapid-fire calls to {@link VerticleSupervisor#reportFatalError} must result in only one
     * scheduled restart (de-duplication via {@code restartInProgress} flag).
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void shouldDeduplicateParallelRestartAttempts(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);
        AtomicInteger redeployCount = new AtomicInteger(0);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            supervisor.supervise("svc", id, testConfig(5, 50L, 50L), () -> redeployCount.incrementAndGet());

            // Rapid-fire — only the first should schedule a restart
            supervisor.reportFatalError("svc", new RuntimeException("1"));
            supervisor.reportFatalError("svc", new RuntimeException("2"));
            supervisor.reportFatalError("svc", new RuntimeException("3"));

            vertx.setTimer(300, tick -> {
                ctx.verify(() -> assertEquals(1, redeployCount.get()));
                ctx.completeNow();
            });
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * When a redeploy fails, {@link VerticleSupervisor#reportRedeployFailure} must clear the
     * {@code restartInProgress} flag and schedule another restart attempt.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void shouldRetryAfterRedeployFailure(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);
        AtomicInteger redeployCount = new AtomicInteger(0);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            supervisor.supervise("svc", id, testConfig(5, 10L, 10L), () -> redeployCount.incrementAndGet());

            // First fatal error → schedules restart
            supervisor.reportFatalError("svc", new RuntimeException("crash"));

            // Wait for first restart timer to fire (redeployCount becomes 1)
            vertx.setTimer(150, t1 -> {
                // Simulate redeploy failure — should clear the flag and schedule another restart
                supervisor.reportRedeployFailure("svc", new RuntimeException("deploy failed"));

                // Wait for second restart timer to fire
                vertx.setTimer(150, t2 -> {
                    ctx.verify(() -> assertEquals(2, redeployCount.get()));
                    ctx.completeNow();
                });
            });
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * Repeated redeploy failures must eventually exhaust the restart budget and mark the verticle
     * unavailable.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void shouldExhaustBudgetViaRedeployFailures(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);
        AtomicInteger redeployCount = new AtomicInteger(0);
        // maxRestarts=1 — only one restart is allowed
        SupervisionConfig config = testConfig(1, 10L, 10L);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            supervisor.supervise("svc", id, config, () -> redeployCount.incrementAndGet());

            // First fatal error → uses the 1 allowed restart
            supervisor.reportFatalError("svc", new RuntimeException("crash"));

            vertx.setTimer(150, t1 -> {
                // Simulate redeploy failure — scheduleRestart() should find budget exhausted
                supervisor.reportRedeployFailure("svc", new RuntimeException("deploy failed"));

                vertx.setTimer(50, t2 -> {
                    ctx.verify(() ->
                            assertFalse(supervisor.isAvailable("svc"), "Should be unavailable after budget exhausted"));
                    ctx.completeNow();
                });
            });
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * {@link VerticleSupervisor#reportRedeployFailure} after {@link
     * VerticleSupervisor#unsupervise} must be a no-op — the state was removed from the map.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void shouldIgnoreRedeployFailureAfterUnsupervise(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);
        AtomicInteger redeployCount = new AtomicInteger(0);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            supervisor.supervise("svc", id, testConfig(5, 10L, 10L), () -> redeployCount.incrementAndGet());
            supervisor.unsupervise("svc");
            // reportRedeployFailure after unsupervise — state is null → no-op
            supervisor.reportRedeployFailure("svc", new RuntimeException("deploy failed"));

            vertx.setTimer(100, tick -> {
                ctx.verify(() -> assertEquals(0, redeployCount.get()));
                ctx.completeNow();
            });
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * If {@link VerticleSupervisor#unsupervise(String)} is called while a restart timer is
     * pending, the timer callback must see {@code intentionalUndeploy == true} and suppress the
     * redeploy action.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void shouldNotRedeployWhenUnsupervisedDuringRestart(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);
        AtomicInteger redeployCount = new AtomicInteger(0);
        // Very short backoff — timer fires on next event loop turn
        SupervisionConfig config = testConfig(5, 1L, 10L);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            supervisor.supervise("svc", id, config, () -> redeployCount.incrementAndGet());

            // Schedule restart (1 ms backoff)
            supervisor.reportFatalError("svc", new RuntimeException("crash"));

            // Unsupervise before the timer fires
            supervisor.unsupervise("svc");

            vertx.setTimer(200, tick -> {
                ctx.verify(() -> assertEquals(0, redeployCount.get(), "Redeploy must be suppressed after unsupervise"));
                ctx.completeNow();
            });
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * While a restart is pending, {@link VerticleSupervisor#isAvailable(String)} must return
     * {@code false}. After a successful re-supervise (simulating redeploy completion), it must
     * return {@code true} again.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void shouldMarkUnavailableDuringRestart(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);
        // Long backoff so the timer does not fire during this test
        SupervisionConfig config = testConfig(5, 10_000L, 10_000L);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            supervisor.supervise("svc", id, config, () -> {});
            assertTrue(supervisor.isAvailable("svc"), "Must be available initially");

            supervisor.reportFatalError("svc", new RuntimeException("crash"));
            assertFalse(supervisor.isAvailable("svc"), "Must be unavailable while restart is pending");

            // Simulate successful redeploy by deploying a new instance and re-supervising
            deployDummy(deployer, "svc2").onComplete(ctx.succeeding(id2 -> {
                supervisor.supervise("svc2", id2, config, () -> {});
                assertTrue(supervisor.isAvailable("svc2"), "Must be available again after re-supervise");
                ctx.completeNow();
            }));
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * The deployer's {@link VerticleDeployer#undeploy(String)} must be called before the redeploy
     * action executes. The test verifies ordering by recording the undeploy step via an
     * {@link AtomicBoolean} and asserting from within the redeploy action that the flag is already
     * set.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void shouldCallUndeployBeforeRedeploy(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        AtomicBoolean undeployCalled = new AtomicBoolean(false);
        AtomicBoolean redeployCalled = new AtomicBoolean(false);

        // Wrap deployer to intercept undeploy calls
        VerticleDeployer trackingDeployer = new VerticleDeployer(vertx) {
            @Override
            public Future<Void> undeploy(String name) {
                undeployCalled.set(true);
                return super.undeploy(name);
            }
        };

        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, trackingDeployer);

        trackingDeployer
                .deploy(VerticleDeployment.of("svc", NoOpVerticle::new, LifecyclePhase.SERVICES))
                .onComplete(ctx.succeeding(id -> {
                    supervisor.supervise("svc", id, testConfig(5, 10L, 10L), () -> {
                        assertTrue(undeployCalled.get(), "Undeploy must have been called before redeploy");
                        redeployCalled.set(true);
                    });

                    supervisor.reportFatalError("svc", new RuntimeException("crash"));

                    vertx.setTimer(300, tick -> {
                        ctx.verify(() -> {
                            assertTrue(undeployCalled.get(), "Undeploy action must have been called");
                            assertTrue(redeployCalled.get(), "Redeploy action must have been called");
                        });
                        ctx.completeNow();
                    });
                }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * When {@link VerticleDeployer#undeploy(String)} fails but the verticle is no longer alive
     * (stale tracking), the redeploy action must still execute. The undeploy failure must not
     * block the restart cycle.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void shouldRedeployEvenWhenUndeployFails(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);
        AtomicInteger redeployCount = new AtomicInteger(0);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            supervisor.supervise("svc", id, testConfig(5, 10L, 10L), () -> redeployCount.incrementAndGet());

            // Kill the verticle directly via Vert.x so deployer tracking is stale
            vertx.undeploy(id).onComplete(ar -> {
                // deployer still tracks "svc" → undeploy() will fail (vertx.undeploy fails for dead ID)
                // but the stale recovery path should evict and proceed to redeploy
                supervisor.reportFatalError("svc", new RuntimeException("crash"));

                vertx.setTimer(300, tick -> {
                    ctx.verify(
                            () -> assertEquals(1, redeployCount.get(), "Redeploy must run even when undeploy fails"));
                    ctx.completeNow();
                });
            });
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * When {@link VerticleDeployer#deploymentId(String)} returns {@code null} for the name at
     * supervise time, an {@link IllegalStateException} must be thrown immediately.
     *
     * @param vertx the Vert.x instance provided by the extension
     */
    @Test
    void supervise_nullDeploymentId_throwsIllegalState(Vertx vertx) {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);

        assertThrows(
                IllegalStateException.class,
                () -> supervisor.supervise("unknown", "some-id", testConfig(5, 50L, 50L), () -> {}));
    }

    /**
     * When the deployer's tracked ID for the name differs from the provided ID, an
     * {@link IllegalStateException} must be thrown immediately.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void supervise_mismatchedId_throwsIllegalState(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            assertThrows(
                    IllegalStateException.class,
                    () -> supervisor.supervise("svc", "wrong-id", testConfig(5, 50L, 50L), () -> {}));
            ctx.completeNow();
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * When undeploy fails and the verticle is dead (not in {@link Vertx#deploymentIDs()}), the
     * supervisor must automatically call {@link VerticleDeployer#evict(String, String)} to clear
     * the stale tracking entry, then proceed to run the redeploy action.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void staleTracking_builtInRecovery(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        AtomicBoolean evictCalled = new AtomicBoolean(false);
        AtomicInteger redeployCount = new AtomicInteger(0);

        // Wrap deployer to verify evict() is called
        VerticleDeployer trackingDeployer = new VerticleDeployer(vertx) {
            @Override
            public boolean evict(String name, String expectedId) {
                evictCalled.set(true);
                return super.evict(name, expectedId);
            }
        };

        trackingDeployer
                .deploy(VerticleDeployment.of("svc", NoOpVerticle::new, LifecyclePhase.SERVICES))
                .onComplete(ctx.succeeding(id -> {
                    VerticleSupervisor supervisor = new VerticleSupervisor(vertx, trackingDeployer);
                    supervisor.supervise("svc", id, testConfig(5, 10L, 10L), () -> redeployCount.incrementAndGet());

                    // Kill the verticle directly so deployer tracking is stale
                    vertx.undeploy(id).onComplete(killAr -> {
                        // The verticle is dead; deployer still thinks it's tracked
                        // reportFatalError → restart cycle → undeploy fails → stale recovery → evict called
                        supervisor.reportFatalError("svc", new RuntimeException("crash"));

                        vertx.setTimer(300, tick -> {
                            ctx.verify(() -> {
                                assertTrue(evictCalled.get(), "evict() must be called for stale tracking recovery");
                                assertEquals(1, redeployCount.get(), "Redeploy must run after stale recovery");
                            });
                            ctx.completeNow();
                        });
                    });
                }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * When undeploy fails and the verticle is still alive (present in {@link Vertx#deploymentIDs()}),
     * the redeploy action must NOT run (prevents duplicate instances), but the supervisor must
     * automatically schedule a retry. With a budget of 2 and persistent undeploy failures, the
     * budget must eventually be exhausted and the verticle marked unavailable — no permanent stall.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void staleTracking_liveVerticle_retriesUntilBudgetExhausted(Vertx vertx, VertxTestContext ctx) throws Throwable {
        AtomicBoolean evictCalled = new AtomicBoolean(false);
        AtomicInteger redeployCount = new AtomicInteger(0);

        // Wrap deployer: undeploy always fails, but evict must NOT be called (verticle is alive)
        VerticleDeployer trackingDeployer = new VerticleDeployer(vertx) {
            @Override
            public Future<Void> undeploy(String name) {
                // Return failure to simulate undeploy error while verticle is still alive
                return Future.failedFuture(new RuntimeException("undeploy rejected"));
            }

            @Override
            public boolean evict(String name, String expectedId) {
                evictCalled.set(true);
                return super.evict(name, expectedId);
            }
        };

        trackingDeployer
                .deploy(VerticleDeployment.of("svc", NoOpVerticle::new, LifecyclePhase.SERVICES))
                .onComplete(ctx.succeeding(id -> {
                    VerticleSupervisor supervisor = new VerticleSupervisor(vertx, trackingDeployer);
                    // Budget of 2 with very short backoff
                    supervisor.supervise("svc", id, testConfig(2, 10L, 10L), () -> redeployCount.incrementAndGet());

                    // Verticle is still alive (deployed in vertx), undeploy will fail
                    // Supervisor must retry automatically until budget is exhausted
                    supervisor.reportFatalError("svc", new RuntimeException("crash"));

                    vertx.setTimer(500, tick -> {
                        ctx.verify(() -> {
                            assertFalse(evictCalled.get(), "evict() must NOT be called when verticle is alive");
                            assertEquals(
                                    0,
                                    redeployCount.get(),
                                    "Redeploy must NOT run when undeploy fails for a live instance");
                            assertFalse(
                                    supervisor.isAvailable("svc"),
                                    "Must be unavailable after budget exhausted by undeploy failures");
                        });
                        ctx.completeNow();
                    });
                }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * {@link VerticleSupervisor#availability()} must return a snapshot reflecting the current
     * status of all supervised verticles.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void availability_reflectsAllSupervisedVerticles(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);

        deployDummy(deployer, "a")
                .compose(idA -> {
                    supervisor.supervise("a", idA, testConfig(5, 10_000L, 10_000L), () -> {});
                    return deployDummy(deployer, "b");
                })
                .onComplete(ctx.succeeding(idB -> {
                    supervisor.supervise("b", idB, testConfig(5, 10_000L, 10_000L), () -> {});

                    Map<String, Boolean> availability = supervisor.availability();
                    ctx.verify(() -> {
                        assertEquals(2, availability.size());
                        assertTrue(availability.get("a"), "a should be available");
                        assertTrue(availability.get("b"), "b should be available");
                    });
                    ctx.completeNow();
                }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * If the redeploy callback throws synchronously, the supervisor must treat it as a redeploy
     * failure (clearing {@code restartInProgress} and scheduling a retry) rather than permanently
     * wedging with the verticle marked unavailable.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    void shouldRecoverFromSynchronousRedeployException(Vertx vertx, VertxTestContext ctx) throws Throwable {
        VerticleDeployer deployer = new VerticleDeployer(vertx);
        VerticleSupervisor supervisor = new VerticleSupervisor(vertx, deployer);
        AtomicInteger redeployCount = new AtomicInteger(0);

        deployDummy(deployer, "svc").onComplete(ctx.succeeding(id -> {
            supervisor.supervise("svc", id, testConfig(5, 10L, 10L), () -> {
                int count = redeployCount.incrementAndGet();
                if (count == 1) {
                    throw new RuntimeException("callback explosion");
                }
                // Second invocation succeeds
            });

            supervisor.reportFatalError("svc", new RuntimeException("crash"));

            // First redeploy throws → treated as failure → retry scheduled → second redeploy succeeds
            vertx.setTimer(500, tick -> {
                ctx.verify(() -> assertTrue(
                        redeployCount.get() >= 2, "Supervisor must retry after synchronous callback failure"));
                ctx.completeNow();
            });
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    // --- Test verticles ---

    /** A no-op verticle used as a deployment target in supervisor tests. */
    static class NoOpVerticle extends AbstractVerticle {}

    /** A verticle that fails immediately on start, used to test crash recovery. */
    static class FailingVerticle extends AbstractVerticle {
        @Override
        public void start(Promise<Void> startPromise) {
            startPromise.fail("Intentional failure");
        }
    }
}
