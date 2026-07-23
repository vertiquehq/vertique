// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.ComposeValidationStep;
import dev.vertique.core.lifecycle.ComposeValidator;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link VertiqueApplicationBootstrap} (the host-neutral lifecycle runner) and the
 * {@link VertiqueApplicationHandle} it returns.
 *
 * <p>The runner is exercised against a fake {@link VertiqueApplicationComponent} whose startup /
 * shutdown steps record their invocation order into a shared list and whose {@link
 * VerticleDeploymentManager} is a Mockito mock that records {@code deployPhase} / {@code undeployAll}
 * calls. The test module has <em>no</em> {@code vertique-launcher} on its classpath, so a green run
 * structurally proves AC-6 host-neutrality.
 *
 * <p>Coverage:
 * <ul>
 *   <li><b>AC-4 / ComposeValidator auto-materialization</b> — a contributed {@link
 *       dev.vertique.core.lifecycle.ComposeValidator} runs in {@code VALIDATE} with no explicit app call
 *       (the {@link dev.vertique.core.lifecycle.ComposeValidationStep} materializes the set), and a
 *       throwing validator fails {@code start()} with the construction exception attributed to {@code
 *       VALIDATE} (no verticle deployed);</li>
 *   <li><b>AC-6 / host-neutral start</b> — phases in enum order, steps sequential by
 *       (priority, orderKey) within a phase, steps before verticles, {@code deployPhase} only for
 *       the four verticle phases;</li>
 *   <li><b>AC-7 / failure teardown</b> — a failing {@code MIGRATE} step (no verticles deployed) and a
 *       failing {@code EDGE} step (after INFRA/SERVICES verticles deployed) each abort with the
 *       original cause, run completed steps' shutdown steps in reverse, and undeploy any deployed
 *       verticles;</li>
 *   <li><b>completed-phase teardown</b> — teardown runs shutdown steps only for phases whose startup
 *       completed: a failing phase's own startup step skips that phase's shutdown step, but a
 *       verticle-deploy failure (whose startup steps did run) still runs that phase's shutdown step;</li>
 *   <li><b>memoized shutdown</b> — all (and concurrent) {@code shutdown()} callers observe the same
 *       {@code Future} and teardown work runs exactly once;</li>
 *   <li><b>diagnostics (NFR-APP-006)</b> — the failure log names the failing step's orderKey;</li>
 *   <li><b>handle.shutdown()</b> — idempotent, reverse shutdown + undeploy, and never closes Vertx.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class VertiqueApplicationBootstrapTest {

    // --- AC-6: host-neutral start ---

    @Test
    @DisplayName("AC-6: start runs phases in enum order, steps before verticles, deploys only verticle phases")
    void start_runsPhasesInOrder_stepsBeforeVerticles_returnsHandle(Vertx vertx, VertxTestContext ctx) {
        List<String> order = new CopyOnWriteArrayList<>();

        // Two CONFIGURE steps with distinct priorities to prove (priority, orderKey) sequencing,
        // plus one step per remaining non-verticle phase and one step in two verticle phases (to
        // prove steps precede that phase's deployPhase).
        Set<ApplicationStartupStep> startup = orderedSet(
                new RecordingStartupStep(order, "configure-late", LifecyclePhase.CONFIGURE, 10),
                new RecordingStartupStep(order, "configure-early", LifecyclePhase.CONFIGURE, 0),
                new RecordingStartupStep(order, "validate", LifecyclePhase.VALIDATE, 0),
                new RecordingStartupStep(order, "infra-step", LifecyclePhase.INFRA, 0),
                new RecordingStartupStep(order, "services-step", LifecyclePhase.SERVICES, 0));

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, order);

        FakeComponent component = new FakeComponent(startup, Set.of(), manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.succeeding(handle -> {
            assertEquals(
                    List.of(
                            "start:configure-early",
                            "start:configure-late",
                            "start:validate",
                            "deploy:BOOTSTRAP",
                            "start:infra-step",
                            "deploy:INFRA",
                            "start:services-step",
                            "deploy:SERVICES",
                            "deploy:EDGE"),
                    order,
                    "phases run in enum order; CONFIGURE steps ordered by priority; each phase's steps precede its deployPhase");

            // deployPhase invoked exactly for the four verticle phases, never for non-verticle phases.
            verify(manager).deployPhase(LifecyclePhase.BOOTSTRAP);
            verify(manager).deployPhase(LifecyclePhase.INFRA);
            verify(manager).deployPhase(LifecyclePhase.SERVICES);
            verify(manager).deployPhase(LifecyclePhase.EDGE);
            verify(manager, never()).deployPhase(LifecyclePhase.CONFIGURE);
            verify(manager, never()).deployPhase(LifecyclePhase.VALIDATE);
            verify(manager, never()).deployPhase(LifecyclePhase.MIGRATE);
            verify(manager, never()).deployPhase(LifecyclePhase.AFTER_START);

            assertSame(component, handle.component(), "handle exposes the built component");
            assertSame(runtime, handle.runtime(), "handle exposes the runtime");
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("AC-6: a host with no startup steps still deploys the four verticle phases and succeeds")
    void start_noSteps_deploysVerticlePhasesOnly(Vertx vertx, VertxTestContext ctx) {
        List<String> order = new CopyOnWriteArrayList<>();
        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, order);

        FakeComponent component = new FakeComponent(Set.of(), Set.of(), manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.succeeding(handle -> {
            assertEquals(List.of("deploy:BOOTSTRAP", "deploy:INFRA", "deploy:SERVICES", "deploy:EDGE"), order);
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("CONFIGURE step start() completes before the VALIDATE step's start() runs (no early materialization)")
    void start_configureRunsBeforeValidateMaterialization(Vertx vertx, VertxTestContext ctx) {
        List<String> order = new CopyOnWriteArrayList<>();

        // A VALIDATE step that records when its start() (the materialization trigger) actually runs,
        // mirroring ComposeValidationStep: the fail-fast work fires in start(), not at construction.
        // Pairing it with a CONFIGURE step proves the runner runs CONFIGURE's start() to completion
        // before it triggers the VALIDATE step's work — so a VALIDATE failure is attributed to VALIDATE,
        // not misattributed to CONFIGURE by an early set materialization.
        Set<ApplicationStartupStep> startup = orderedSet(
                new RecordingStartupStep(order, "validate", LifecyclePhase.VALIDATE, 0),
                new RecordingStartupStep(order, "configure", LifecyclePhase.CONFIGURE, 0));

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, order);

        FakeComponent component = new FakeComponent(startup, Set.of(), manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.succeeding(handle -> {
            int configureStart = order.indexOf("start:configure");
            int validateStart = order.indexOf("start:validate");
            assertTrue(configureStart >= 0, "CONFIGURE step start() must have run");
            assertTrue(validateStart >= 0, "VALIDATE step start() must have run");
            assertTrue(
                    configureStart < validateStart,
                    "CONFIGURE step start() must complete before the VALIDATE step's start() (its materialization) runs");
            ctx.completeNow();
        }));
    }

    // --- AC-4: ComposeValidator automatic materialization ---

    /**
     * AC-4 positive: a {@link ComposeValidator} contributed via the {@link ComposeValidationStep}
     * provider runs during VALIDATE <em>without</em> any explicit {@code component.someValidator()}
     * call. The test wires a {@code ComposeValidationStep} into the component's startup-step set and
     * asserts that:
     * <ol>
     *   <li>the flag is {@code false} <em>before</em> {@code start(...)} is called — proving the
     *       validator has not been constructed at test-setup time;</li>
     *   <li>the flag is {@code true} <em>after</em> {@code start(...)} completes — proving the
     *       runner materialized the provider (and thus constructed the validator) during VALIDATE,
     *       with no explicit call from the test.</li>
     * </ol>
     *
     * <p>The validator is constructed <em>inside</em> the {@code Provider<Set<ComposeValidator>>}
     * lambda so the construction side-effect fires only when the runner calls {@code provider.get()}
     * during VALIDATE — not at test-setup time.
     */
    @Test
    @DisplayName(
            "AC-4: a contributed ComposeValidator runs in VALIDATE with no explicit app call (runner materializes the set)")
    void start_composeValidatorRunsAutomatically_noExplicitCall(Vertx vertx, VertxTestContext ctx) {
        AtomicBoolean validatorConstructed = new AtomicBoolean(false);

        // The provider builds the validator on first get(); construction flips the flag.
        // Building inside the lambda (not at setup) means the flag is false until the runner
        // calls provider.get() — i.e. until ComposeValidationStep.start() runs in VALIDATE.
        ComposeValidationStep validationStep =
                new ComposeValidationStep(() -> Set.of(new RecordingComposeValidator(validatorConstructed)));

        // The component exposes the ComposeValidationStep as a VALIDATE ApplicationStartupStep —
        // exactly how CoreLifecycleStepsModule contributes it. No explicit call to the step or
        // validator is made by the test; the runner drives it.
        Set<ApplicationStartupStep> startup = orderedSet(validationStep);

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, new CopyOnWriteArrayList<>());

        FakeComponent component = new FakeComponent(startup, Set.of(), manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        // Assert the flag is false before start() — the validator has not been constructed yet.
        assertFalse(validatorConstructed.get(), "validator must not be constructed before start() is called");

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.succeeding(handle -> {
            assertTrue(
                    validatorConstructed.get(),
                    "the ComposeValidator's provider.get() must have been called by ComposeValidationStep.start() "
                            + "during VALIDATE — with no explicit component.someValidator() call from the test");
            ctx.completeNow();
        }));
    }

    /**
     * AC-4 negative: a {@link ComposeValidator} whose construction throws causes {@code start(...)}
     * to fail, and the failure is attributed to the {@link LifecyclePhase#VALIDATE VALIDATE} phase
     * (not CONFIGURE or any earlier phase). The test asserts that the failed future's cause is the
     * validator's construction exception.
     */
    @Test
    @DisplayName(
            "AC-4: a ComposeValidator whose construction throws causes start() to fail with the validator's exception")
    void start_throwingComposeValidator_failsStartWithValidatorException(Vertx vertx, VertxTestContext ctx) {
        IllegalStateException validationException = new IllegalStateException("compose validation failed: missing dep");

        // The throwing provider simulates a constructible-as-validation check that fails.
        ComposeValidationStep validationStep = new ComposeValidationStep(() -> {
            throw validationException;
        });

        Set<ApplicationStartupStep> startup = orderedSet(validationStep);

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, new CopyOnWriteArrayList<>());

        FakeComponent component = new FakeComponent(startup, Set.of(), manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.failing(cause -> {
            assertSame(
                    validationException,
                    cause,
                    "start() must fail with the validator's construction exception, not a wrapper");
            assertInstanceOf(
                    IllegalStateException.class, cause, "the failure must carry the validator's IllegalStateException");
            // The failure is attributed to VALIDATE: no verticle was ever deployed.
            verify(manager, never()).deployPhase(LifecyclePhase.BOOTSTRAP);
            ctx.completeNow();
        }));
    }

    // --- AC-7: failure teardown ---

    @Test
    @DisplayName("AC-7: a failing MIGRATE step aborts before any verticle, runs completed shutdown steps in reverse")
    void start_failingMigrateStep_tearsDownWithoutDeployingVerticles(Vertx vertx, VertxTestContext ctx) {
        List<String> order = new CopyOnWriteArrayList<>();
        RuntimeException boom = new RuntimeException("migrate boom");

        Set<ApplicationStartupStep> startup = orderedSet(
                new RecordingStartupStep(order, "configure", LifecyclePhase.CONFIGURE, 0),
                new RecordingStartupStep(order, "validate", LifecyclePhase.VALIDATE, 0),
                new FailingStartupStep(order, "migrate-fail", LifecyclePhase.MIGRATE, boom));

        // Shutdown steps paired with the completed startup phases (CONFIGURE, VALIDATE) plus the
        // failing phase (MIGRATE). MIGRATE's startup step failed, so MIGRATE is NOT a completed phase
        // and its shutdown step must NOT run — only the completed phases' shutdown steps run, in reverse.
        Set<ApplicationShutdownStep> shutdown = orderedSet(
                new RecordingShutdownStep(order, "configure", LifecyclePhase.CONFIGURE, 0),
                new RecordingShutdownStep(order, "validate", LifecyclePhase.VALIDATE, 0),
                new RecordingShutdownStep(order, "migrate", LifecyclePhase.MIGRATE, 0));

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, order);
        recordUndeployAll(manager, order);

        FakeComponent component = new FakeComponent(startup, shutdown, manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.failing(cause -> {
            assertSame(boom, cause, "the original startup cause propagates");
            assertEquals(
                    List.of(
                            "start:configure",
                            "start:validate",
                            "start:migrate-fail",
                            "stop:validate",
                            "stop:configure"),
                    order,
                    "completed steps ran; shutdown steps ran in reverse for COMPLETED phases only — the "
                            + "failing MIGRATE phase's shutdown step does not run");

            // No verticle phase was ever reached → no deploy and no undeploy.
            verify(manager, never()).deployPhase(LifecyclePhase.BOOTSTRAP);
            verify(manager, never()).undeployAll();
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("AC-7: a failing EDGE step after INFRA/SERVICES deploy tears down verticles and propagates the cause")
    void start_failingEdgeStep_undeploysDeployedVerticles(Vertx vertx, VertxTestContext ctx) {
        List<String> order = new CopyOnWriteArrayList<>();
        RuntimeException boom = new RuntimeException("edge boom");

        Set<ApplicationStartupStep> startup = orderedSet(
                new RecordingStartupStep(order, "infra-step", LifecyclePhase.INFRA, 0),
                new RecordingStartupStep(order, "services-step", LifecyclePhase.SERVICES, 0),
                new FailingStartupStep(order, "edge-fail", LifecyclePhase.EDGE, boom));

        Set<ApplicationShutdownStep> shutdown = orderedSet(
                new RecordingShutdownStep(order, "infra", LifecyclePhase.INFRA, 0),
                new RecordingShutdownStep(order, "services", LifecyclePhase.SERVICES, 0));

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, order);
        recordUndeployAll(manager, order);

        FakeComponent component = new FakeComponent(startup, shutdown, manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.failing(cause -> {
            assertSame(boom, cause, "the original startup cause propagates");
            assertEquals(
                    List.of(
                            "deploy:BOOTSTRAP",
                            "start:infra-step",
                            "deploy:INFRA",
                            "start:services-step",
                            "deploy:SERVICES",
                            "start:edge-fail",
                            "stop:services",
                            "stop:infra",
                            "undeployAll"),
                    order,
                    "verticles deployed up to the failure; shutdown steps reverse-ordered; then verticle undeploy");

            verify(manager).undeployAll();
            verify(manager, never()).deployPhase(LifecyclePhase.EDGE);
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("AC-7: a factory that throws fails the start future with the original exception")
    void start_factoryThrows_failsWithCause(Vertx vertx, VertxTestContext ctx) {
        IllegalStateException boom = new IllegalStateException("factory boom");
        VertiqueComponentFactory<FakeComponent> factory = rt -> {
            throw boom;
        };
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, factory).onComplete(ctx.failing(cause -> {
            assertSame(boom, cause);
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("AC-7: component.startupSteps() throwing returns a failed future (no synchronous throw escapes)")
    void start_startupStepsThrows_failsWithCause(Vertx vertx, VertxTestContext ctx) {
        // A Dagger Provider backing a multibinding that throws during set materialization must not
        // escape as a synchronous throw from start() — it must produce a failed future. This test
        // uses a component whose startupSteps() accessor throws to prove the snapshot is guarded by
        // the same try/catch as factory.build().
        IllegalStateException boom = new IllegalStateException("startupSteps materialization failure");

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        ThrowingStartupStepsComponent component = new ThrowingStartupStepsComponent(manager, boom);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        // assertDoesNotThrow proves start() does not throw synchronously; the returned future must
        // carry the boom as its failure cause.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> VertiqueApplicationBootstrap.start(
                        runtime, rt -> component)
                .onComplete(ctx.failing(cause -> {
                    assertSame(boom, cause, "the startupSteps() throw must surface as the failed future's cause");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("AC-7: component.shutdownSteps() throwing returns a failed future (no synchronous throw escapes)")
    void start_shutdownStepsThrows_failsWithCause(Vertx vertx, VertxTestContext ctx) {
        // Same guard but for shutdownSteps() — both snapshot calls are inside the try.
        IllegalStateException boom = new IllegalStateException("shutdownSteps materialization failure");

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        ThrowingShutdownStepsComponent component = new ThrowingShutdownStepsComponent(manager, boom);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> VertiqueApplicationBootstrap.start(
                        runtime, rt -> component)
                .onComplete(ctx.failing(cause -> {
                    assertSame(boom, cause, "the shutdownSteps() throw must surface as the failed future's cause");
                    ctx.completeNow();
                })));
    }

    // --- Completed-phase teardown ---

    @Test
    @DisplayName(
            "completed-phase teardown: a failing MIGRATE step runs shutdown only for CONFIGURE/VALIDATE, not MIGRATE/SERVICES")
    void teardown_failingPhaseStep_runsShutdownOnlyForCompletedPhases(Vertx vertx, VertxTestContext ctx) {
        List<String> order = new CopyOnWriteArrayList<>();
        RuntimeException boom = new RuntimeException("migrate boom");

        // MIGRATE's own startup step fails, so MIGRATE's startup never completes; SERVICES is never
        // reached. Only CONFIGURE and VALIDATE complete their startup.
        Set<ApplicationStartupStep> startup = orderedSet(
                new RecordingStartupStep(order, "configure", LifecyclePhase.CONFIGURE, 0),
                new RecordingStartupStep(order, "validate", LifecyclePhase.VALIDATE, 0),
                new FailingStartupStep(order, "migrate-fail", LifecyclePhase.MIGRATE, boom));

        // Shutdown steps contributed at CONFIGURE/VALIDATE/MIGRATE/SERVICES: only the completed phases'
        // (CONFIGURE, VALIDATE) shutdown steps must run, in reverse.
        Set<ApplicationShutdownStep> shutdown = orderedSet(
                new RecordingShutdownStep(order, "configure", LifecyclePhase.CONFIGURE, 0),
                new RecordingShutdownStep(order, "validate", LifecyclePhase.VALIDATE, 0),
                new RecordingShutdownStep(order, "migrate", LifecyclePhase.MIGRATE, 0),
                new RecordingShutdownStep(order, "services", LifecyclePhase.SERVICES, 0));

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, order);
        recordUndeployAll(manager, order);

        FakeComponent component = new FakeComponent(startup, shutdown, manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.failing(cause -> {
            assertSame(boom, cause, "the original startup cause propagates");
            assertEquals(
                    List.of(
                            "start:configure",
                            "start:validate",
                            "start:migrate-fail",
                            "stop:validate",
                            "stop:configure"),
                    order,
                    "shutdown runs only for completed phases (CONFIGURE, VALIDATE) in reverse; the failing "
                            + "MIGRATE phase's step and the never-reached SERVICES phase's step do not run");
            verify(manager, never()).undeployAll();
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("completed-phase teardown: a SERVICES deploy failure (after its steps ran) still runs the SERVICES "
            + "shutdown step and undeploy")
    void teardown_verticleDeployFailure_stillRunsThatPhasesShutdownStep(Vertx vertx, VertxTestContext ctx) {
        List<String> order = new CopyOnWriteArrayList<>();
        RuntimeException boom = new RuntimeException("services deploy boom");

        // SERVICES' startup step completes, but SERVICES' verticle deploy fails. The phase's startup
        // is still counted as complete (its steps ran), so the SERVICES shutdown step must run.
        Set<ApplicationStartupStep> startup =
                orderedSet(new RecordingStartupStep(order, "services-step", LifecyclePhase.SERVICES, 0));

        Set<ApplicationShutdownStep> shutdown = orderedSet(
                new RecordingShutdownStep(order, "bootstrap", LifecyclePhase.BOOTSTRAP, 0),
                new RecordingShutdownStep(order, "infra", LifecyclePhase.INFRA, 0),
                new RecordingShutdownStep(order, "services", LifecyclePhase.SERVICES, 0),
                new RecordingShutdownStep(order, "edge", LifecyclePhase.EDGE, 0));

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, order);
        recordUndeployAll(manager, order);
        failDeployPhase(manager, order, LifecyclePhase.SERVICES, boom);

        FakeComponent component = new FakeComponent(startup, shutdown, manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.failing(cause -> {
            assertSame(boom, cause, "the original deploy cause propagates");
            assertEquals(
                    List.of(
                            "deploy:BOOTSTRAP",
                            "deploy:INFRA",
                            "start:services-step",
                            "deploy:SERVICES",
                            "stop:services",
                            "stop:infra",
                            "stop:bootstrap",
                            "undeployAll"),
                    order,
                    "BOOTSTRAP/INFRA/SERVICES startup completed (their steps ran) so their shutdown steps run "
                            + "in reverse; EDGE was never reached so its shutdown step does not; undeploy runs because "
                            + "verticle phases deployed");
            verify(manager).undeployAll();
            verify(manager, never()).deployPhase(LifecyclePhase.EDGE);
            ctx.completeNow();
        }));
    }

    // --- Memoized shutdown ---

    @Test
    @DisplayName("memoized shutdown: all callers observe the same Future and teardown work runs exactly once")
    void shutdown_isMemoized_runsTeardownExactlyOnce(Vertx vertx, VertxTestContext ctx) {
        AtomicInteger stopCount = new AtomicInteger();

        Set<ApplicationStartupStep> startup =
                orderedSet(new RecordingStartupStep(new CopyOnWriteArrayList<>(), "infra", LifecyclePhase.INFRA, 0));
        Set<ApplicationShutdownStep> shutdown =
                orderedSet(new CountingShutdownStep(stopCount, "infra", LifecyclePhase.INFRA));

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, new CopyOnWriteArrayList<>());
        when(manager.undeployAll()).thenReturn(Future.succeededFuture());

        FakeComponent component = new FakeComponent(startup, shutdown, manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.succeeding(handle -> {
            // Concurrent-ish calls on the same context: the first creates the teardown future, the
            // second must return the very same instance, never a fresh succeeded future.
            Future<Void> first = handle.shutdown();
            Future<Void> second = handle.shutdown();
            assertSame(first, second, "both shutdown() calls must return the same memoized Future instance");

            first.onComplete(ctx.succeeding(v1 -> second.onComplete(ctx.succeeding(v2 -> {
                assertEquals(1, stopCount.get(), "teardown work (the shutdown step) must run exactly once");
                // A third call after settlement still returns the same memoized future and runs nothing more.
                Future<Void> third = handle.shutdown();
                assertSame(first, third, "a post-settlement shutdown() returns the same memoized Future");
                assertEquals(1, stopCount.get(), "no additional teardown work after memoized future settled");
                ctx.completeNow();
            }))));
        }));
    }

    // --- Diagnostics (NFR-APP-006) ---

    @Test
    @DisplayName("diagnostics: the startup-failure log names the failing step's orderKey")
    void diagnostics_failureLog_namesFailingStep(Vertx vertx, VertxTestContext ctx) {
        List<String> order = new CopyOnWriteArrayList<>();
        RuntimeException boom = new RuntimeException("migrate boom");

        Set<ApplicationStartupStep> startup = orderedSet(
                new RecordingStartupStep(order, "configure", LifecyclePhase.CONFIGURE, 0),
                new FailingStartupStep(order, "migrate-fail-step", LifecyclePhase.MIGRATE, boom));

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, order);

        FakeComponent component = new FakeComponent(startup, Set.of(), manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        // Attach a list appender to the runner's logger to capture the failure diagnostic.
        Logger runnerLogger = (Logger) LoggerFactory.getLogger(VertiqueApplicationBootstrap.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        runnerLogger.addAppender(appender);

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.failing(cause -> {
            runnerLogger.detachAppender(appender);
            assertSame(boom, cause, "the original cause propagates unwrapped");

            String failureLog = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("startup failed"))
                    .findFirst()
                    .orElse(null);
            assertTrue(failureLog != null, "an ERROR startup-failure log must be emitted");
            assertTrue(
                    failureLog.contains("migrate-fail-step"),
                    "the failure diagnostic must name the failing step's orderKey; got: " + failureLog);
            assertTrue(
                    failureLog.contains(LifecyclePhase.MIGRATE.name()),
                    "the failure diagnostic must name the failing phase; got: " + failureLog);
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName(
            "diagnostics: failure cause message is NOT in the log but the cause propagates unwrapped on the future")
    void start_failureCauseMessageNotLogged_butPropagated(Vertx vertx, VertxTestContext ctx) {
        // A startup step whose failure message embeds a sentinel that must NOT appear in the runner's
        // ERROR log. The cause must still propagate unwrapped on the returned failed future.
        final String sentinel = "SECRET_sentinel_9f3a leaked";
        IllegalStateException boom = new IllegalStateException("config value " + sentinel);

        Set<ApplicationStartupStep> startup = orderedSet(new FailingStartupStep(
                new CopyOnWriteArrayList<>(), "migrate-sensitive", LifecyclePhase.MIGRATE, boom));

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, new CopyOnWriteArrayList<>());

        FakeComponent component = new FakeComponent(startup, Set.of(), manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        // Attach a list appender to capture all log output from the runner.
        Logger runnerLogger = (Logger) LoggerFactory.getLogger(VertiqueApplicationBootstrap.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        runnerLogger.addAppender(appender);

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.failing(cause -> {
            runnerLogger.detachAppender(appender);

            // (a) The cause propagates UNWRAPPED — same instance, not wrapped.
            assertSame(boom, cause, "the original cause must propagate unwrapped on the failed future");

            // Collect the full text of every logged event (formatted message + any appended throwable
            // text) so the sentinel check is exhaustive.
            String allLogText = appender.list.stream()
                    .map(e -> {
                        StringBuilder sb = new StringBuilder(e.getFormattedMessage());
                        // ILoggingEvent.getThrowableProxy() is non-null when cause was passed as a
                        // throwable arg; if the runner accidentally logs the cause, the proxy message
                        // appears in the appended stack trace captured here via toString().
                        if (e.getThrowableProxy() != null) {
                            sb.append(' ').append(e.getThrowableProxy().getMessage());
                        }
                        return sb.toString();
                    })
                    .reduce("", (a, b) -> a + '\n' + b);

            // (b) The failure ERROR log contains the step's orderKey and the exception class name.
            String failureLog = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("startup failed"))
                    .findFirst()
                    .orElse(null);
            assertTrue(failureLog != null, "an ERROR startup-failure log must be emitted");
            assertTrue(
                    failureLog.contains("migrate-sensitive"),
                    "the failure log must contain the step's orderKey; got: " + failureLog);
            assertTrue(
                    failureLog.contains(IllegalStateException.class.getName()),
                    "the failure log must contain the cause's class name; got: " + failureLog);

            // (c) The sentinel value must NOT appear anywhere in the captured log output —
            //     proving the runner does not log the raw cause message.
            assertFalse(
                    allLogText.contains(sentinel),
                    "the runner must NOT log the cause's value-bearing message; sentinel found in: " + allLogText);

            ctx.completeNow();
        }));
    }

    // --- Generic handle (compile-time) ---

    @Test
    @DisplayName("generic handle: handle.component() returns the concrete component type without a cast")
    void handle_component_returnsConcreteTypeWithoutCast(Vertx vertx, VertxTestContext ctx) {
        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, new CopyOnWriteArrayList<>());

        FakeComponent component = new FakeComponent(Set.of(), Set.of(), manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueComponentFactory<FakeComponent> factory = rt -> component;
        VertiqueApplicationBootstrap.start(runtime, factory).onComplete(ctx.succeeding(handle -> {
            // The compile-time proof: handle.component() is FakeComponent, assignable without a cast.
            FakeComponent typed = handle.component();
            assertSame(component, typed, "the generic handle exposes the concrete component type directly");
            ctx.completeNow();
        }));
    }

    // --- handle.shutdown() ---

    @Test
    @DisplayName("handle.shutdown() runs shutdown steps in reverse, undeploys verticles, and never closes Vertx")
    void shutdown_reverseStepsAndUndeploy_neverClosesVertx(Vertx realVertx, VertxTestContext ctx) {
        List<String> order = new CopyOnWriteArrayList<>();

        Set<ApplicationStartupStep> startup = orderedSet(
                new RecordingStartupStep(order, "infra-step", LifecyclePhase.INFRA, 0),
                new RecordingStartupStep(order, "services-step", LifecyclePhase.SERVICES, 0));
        Set<ApplicationShutdownStep> shutdown = orderedSet(
                new RecordingShutdownStep(order, "infra", LifecyclePhase.INFRA, 0),
                new RecordingShutdownStep(order, "services", LifecyclePhase.SERVICES, 0));

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, order);
        recordUndeployAll(manager, order);

        FakeComponent component = new FakeComponent(startup, shutdown, manager);

        // Use a Mockito Vertx mock for the runtime so we can assert close() is never invoked.
        Vertx vertxMock = mock(Vertx.class);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertxMock, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.succeeding(handle -> {
            order.clear();
            handle.shutdown().onComplete(ctx.succeeding(v1 -> {
                assertEquals(
                        List.of("stop:services", "stop:infra", "undeployAll"),
                        order,
                        "shutdown runs steps in reverse then undeploys verticles");

                // Second shutdown is an idempotent no-op (records nothing more).
                handle.shutdown().onComplete(ctx.succeeding(v2 -> {
                    assertEquals(
                            List.of("stop:services", "stop:infra", "undeployAll"), order, "second shutdown is a no-op");
                    verify(vertxMock, never()).close();
                    ctx.completeNow();
                }));
            }));
        }));
    }

    @Test
    @DisplayName("handle.shutdown() swallows a failing shutdown step and still completes teardown")
    void shutdown_failingStep_isSwallowedAndTeardownContinues(Vertx vertx, VertxTestContext ctx) {
        List<String> order = new CopyOnWriteArrayList<>();

        Set<ApplicationStartupStep> startup =
                orderedSet(new RecordingStartupStep(order, "infra-step", LifecyclePhase.INFRA, 0));
        Set<ApplicationShutdownStep> shutdown = orderedSet(
                new FailingShutdownStep(order, "infra-fail", LifecyclePhase.INFRA, new RuntimeException("stop boom")));

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, order);
        recordUndeployAll(manager, order);

        FakeComponent component = new FakeComponent(startup, shutdown, manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.succeeding(handle -> {
            order.clear();
            handle.shutdown().onComplete(ctx.succeeding(v -> {
                assertEquals(
                        List.of("stop:infra-fail", "undeployAll"),
                        order,
                        "the failing stop is observed and swallowed; undeploy still runs");
                ctx.completeNow();
            }));
        }));
    }

    @Test
    @DisplayName("steps and verticles in a phase: the verticle deploy waits for that phase's steps to complete")
    void phaseOrdering_verticleDeployFollowsItsPhaseSteps(Vertx vertx, VertxTestContext ctx) {
        List<String> order = new CopyOnWriteArrayList<>();

        // Two INFRA steps at different priorities; both must run before deploy:INFRA.
        Set<ApplicationStartupStep> startup = orderedSet(
                new RecordingStartupStep(order, "infra-b", LifecyclePhase.INFRA, 5),
                new RecordingStartupStep(order, "infra-a", LifecyclePhase.INFRA, 1));

        VerticleDeploymentManager manager = mock(VerticleDeploymentManager.class);
        recordDeployPhase(manager, order);

        FakeComponent component = new FakeComponent(startup, Set.of(), manager);
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());

        VertiqueApplicationBootstrap.start(runtime, rt -> component).onComplete(ctx.succeeding(handle -> {
            assertEquals(
                    List.of(
                            "deploy:BOOTSTRAP",
                            "start:infra-a",
                            "start:infra-b",
                            "deploy:INFRA",
                            "deploy:SERVICES",
                            "deploy:EDGE"),
                    order);
            assertTrue(order.indexOf("start:infra-b") < order.indexOf("deploy:INFRA"));
            assertFalse(order.isEmpty());
            ctx.completeNow();
        }));
    }

    // --- Test helpers ---

    /**
     * Wraps the given participants in an insertion-ordered {@link LinkedHashSet}. The runner sorts by
     * {@code LifecycleOrdered.comparator()}, so insertion order must not be relied upon for the
     * assertion — using a {@code LinkedHashSet} that is deliberately <em>not</em> in execution order
     * makes the sort observable.
     *
     * @param items the participants
     * @param <T> the participant type
     * @return an insertion-ordered set of the items
     */
    @SafeVarargs
    private static <T> Set<T> orderedSet(T... items) {
        return new LinkedHashSet<>(List.of(items));
    }

    /**
     * Stubs {@code deployPhase(phase)} on the mock manager to record {@code "deploy:<PHASE>"} and
     * return a succeeded future, for every {@link LifecyclePhase}.
     *
     * @param manager the mock deployment manager
     * @param order the shared invocation-order recorder
     */
    private static void recordDeployPhase(VerticleDeploymentManager manager, List<String> order) {
        for (LifecyclePhase phase : LifecyclePhase.values()) {
            when(manager.deployPhase(phase)).thenAnswer(inv -> {
                order.add("deploy:" + phase.name());
                return Future.succeededFuture();
            });
        }
    }

    /**
     * Stubs {@code undeployAll()} on the mock manager to record {@code "undeployAll"} and return a
     * succeeded future.
     *
     * @param manager the mock deployment manager
     * @param order the shared invocation-order recorder
     */
    private static void recordUndeployAll(VerticleDeploymentManager manager, List<String> order) {
        when(manager.undeployAll()).thenAnswer(inv -> {
            order.add("undeployAll");
            return Future.succeededFuture();
        });
    }

    /**
     * Overrides the {@code deployPhase(failingPhase)} stub on an already-{@link #recordDeployPhase
     * recorded} mock manager so that one phase's deploy records {@code "deploy:<PHASE>"} and then fails
     * with the given cause, leaving the other phases succeeding. Used to prove that a verticle-deploy
     * failure (whose phase's startup steps did run) still counts the phase's startup as complete.
     *
     * @param manager the mock deployment manager (already recorded for all phases)
     * @param order the shared invocation-order recorder
     * @param failingPhase the phase whose deploy should fail
     * @param cause the failure cause
     */
    private static void failDeployPhase(
            VerticleDeploymentManager manager, List<String> order, LifecyclePhase failingPhase, Throwable cause) {
        // doAnswer(...).when(...) avoids invoking the already-recorded deployPhase stub during setup
        // (which when(manager.deployPhase(...)) would, recording a spurious "deploy:" entry).
        doAnswer(inv -> {
                    order.add("deploy:" + failingPhase.name());
                    return Future.failedFuture(cause);
                })
                .when(manager)
                .deployPhase(failingPhase);
    }

    // --- Test doubles ---

    /** Fake passive component returning fixed step sets and a (mock) deployment manager. */
    private record FakeComponent(
            Set<ApplicationStartupStep> startupSteps,
            Set<ApplicationShutdownStep> shutdownSteps,
            VerticleDeploymentManager verticleDeploymentManager)
            implements VertiqueApplicationComponent {}

    /**
     * A component whose {@link #startupSteps()} throws a fixed cause on the first call, used to
     * prove that the snapshot is guarded inside the same {@code try/catch} as {@code factory.build()}
     * — so a Dagger {@code Provider} that throws during set materialization produces a failed future
     * rather than a synchronous throw escaping {@link VertiqueApplicationBootstrap#start}.
     */
    private static final class ThrowingStartupStepsComponent implements VertiqueApplicationComponent {

        private final VerticleDeploymentManager manager;
        private final RuntimeException cause;

        /**
         * @param manager the deployment manager (never reached; the startupSteps() throw aborts before)
         * @param cause the exception to throw from {@link #startupSteps()}
         */
        ThrowingStartupStepsComponent(VerticleDeploymentManager manager, RuntimeException cause) {
            this.manager = manager;
            this.cause = cause;
        }

        @Override
        public Set<ApplicationStartupStep> startupSteps() {
            throw cause;
        }

        @Override
        public Set<ApplicationShutdownStep> shutdownSteps() {
            return Set.of();
        }

        @Override
        public VerticleDeploymentManager verticleDeploymentManager() {
            return manager;
        }
    }

    /**
     * A component whose {@link #shutdownSteps()} throws a fixed cause on the first call, used to
     * prove that the shutdown-step snapshot is guarded inside the same {@code try/catch} as the
     * startup-step snapshot — so a Dagger {@code Provider} that throws during set materialization
     * produces a failed future rather than a synchronous throw escaping
     * {@link VertiqueApplicationBootstrap#start}.
     */
    private static final class ThrowingShutdownStepsComponent implements VertiqueApplicationComponent {

        private final VerticleDeploymentManager manager;
        private final RuntimeException cause;

        /**
         * @param manager the deployment manager (never reached; the shutdownSteps() throw aborts before)
         * @param cause the exception to throw from {@link #shutdownSteps()}
         */
        ThrowingShutdownStepsComponent(VerticleDeploymentManager manager, RuntimeException cause) {
            this.manager = manager;
            this.cause = cause;
        }

        @Override
        public Set<ApplicationStartupStep> startupSteps() {
            return Set.of();
        }

        @Override
        public Set<ApplicationShutdownStep> shutdownSteps() {
            throw cause;
        }

        @Override
        public VerticleDeploymentManager verticleDeploymentManager() {
            return manager;
        }
    }

    /** A startup step that records {@code "start:<name>"} and succeeds. */
    private record RecordingStartupStep(List<String> order, String name, LifecyclePhase phase, int priority)
            implements ApplicationStartupStep {
        @Override
        public String orderKey() {
            return name;
        }

        @Override
        public Future<Void> start() {
            order.add("start:" + name);
            return Future.succeededFuture();
        }
    }

    /** A startup step that records {@code "start:<name>"} and then fails with a fixed cause. */
    private record FailingStartupStep(List<String> order, String name, LifecyclePhase phase, Throwable cause)
            implements ApplicationStartupStep {
        @Override
        public String orderKey() {
            return name;
        }

        @Override
        public Future<Void> start() {
            order.add("start:" + name);
            return Future.failedFuture(cause);
        }
    }

    /** A shutdown step that records {@code "stop:<name>"} and succeeds. */
    private record RecordingShutdownStep(List<String> order, String name, LifecyclePhase phase, int priority)
            implements ApplicationShutdownStep {
        @Override
        public String orderKey() {
            return name;
        }

        @Override
        public Future<Void> stop() {
            order.add("stop:" + name);
            return Future.succeededFuture();
        }
    }

    /** A shutdown step that records {@code "stop:<name>"} and then fails (must be swallowed). */
    private record FailingShutdownStep(List<String> order, String name, LifecyclePhase phase, Throwable cause)
            implements ApplicationShutdownStep {
        @Override
        public String orderKey() {
            return name;
        }

        @Override
        public Future<Void> stop() {
            order.add("stop:" + name);
            return Future.failedFuture(cause);
        }
    }

    /**
     * A shutdown step that increments a shared counter each time its {@code stop()} runs and succeeds.
     * Used by the memoization test to prove teardown work runs exactly once across multiple (and
     * concurrent) {@code shutdown()} calls.
     */
    private record CountingShutdownStep(AtomicInteger count, String name, LifecyclePhase phase)
            implements ApplicationShutdownStep {
        @Override
        public String orderKey() {
            return name;
        }

        @Override
        public Future<Void> stop() {
            count.incrementAndGet();
            return Future.succeededFuture();
        }
    }

    /**
     * A {@link ComposeValidator} whose construction flips a shared flag, proving that the
     * {@link dev.vertique.core.lifecycle.ComposeValidationStep} materialized the validator set (and
     * thus constructed this instance) during the {@link LifecyclePhase#VALIDATE VALIDATE} phase —
     * without the test calling any validator method directly. The construction side-effect is the
     * observable proof.
     */
    private static final class RecordingComposeValidator implements ComposeValidator {

        private final AtomicBoolean constructionFlag;

        /**
         * Constructs the validator and immediately records construction by flipping the flag.
         *
         * @param constructionFlag the flag to flip on construction
         */
        RecordingComposeValidator(AtomicBoolean constructionFlag) {
            this.constructionFlag = constructionFlag;
            this.constructionFlag.set(true);
        }
    }
}
