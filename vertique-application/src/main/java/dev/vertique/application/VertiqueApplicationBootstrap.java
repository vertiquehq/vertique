// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application;

import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The framework-owned, host-neutral lifecycle runner.
 *
 * <p>{@link #start(VertiqueRuntime, VertiqueComponentFactory)} builds the application's {@link
 * VertiqueApplicationComponent} from a {@link VertiqueComponentFactory} and then drives the lifecycle
 * deterministically: for each {@link LifecyclePhase} in declaration order it runs that phase's
 * {@link ApplicationStartupStep}s sequentially (ordered by {@link LifecycleOrdered#comparator()}),
 * then deploys that phase's verticles (for the {@link LifecyclePhase#isVerticlePhase() verticle
 * subset}). Startup is <strong>fail-closed</strong>: the first failure aborts and tears down in
 * reverse order, then the original cause propagates.
 *
 * <p>This runner depends only on {@code vertique-core} and {@code vertique-deploy} — never on {@code
 * vertique-launcher} or any host (Spring, Quarkus) type. The standalone path uses it; embedding host
 * bridges may adopt it. It <strong>never</strong> closes the {@code Vertx} instance (host-owned).
 *
 * <p>The class is non-instantiable; all behavior is on the static {@link #start} entry point.
 */
public final class VertiqueApplicationBootstrap {

    private static final Logger log = LoggerFactory.getLogger(VertiqueApplicationBootstrap.class);

    private VertiqueApplicationBootstrap() {}

    /**
     * Builds the application component and drives the host-neutral lifecycle to a started handle.
     *
     * <p>Execution, per phase in {@link LifecyclePhase} declaration order: run that phase's startup
     * steps sequentially in {@link LifecycleOrdered#comparator()} order (fail-fast — the chain stops
     * on the first failed step), then, for a {@link LifecyclePhase#isVerticlePhase() verticle-subset}
     * phase, deploy that phase's verticles via {@link
     * dev.vertique.deploy.VerticleDeploymentManager#deployPhase(LifecyclePhase)}. On any failure the
     * runner tears down — reverse-order shutdown steps for the phases whose startup completed, then
     * verticle undeploy — and fails the returned future with the <em>original</em> cause. The {@code
     * Vertx} instance is never closed.
     *
     * <p>The failure log records only value-free identity ({@code runningUnit}, {@code reachedPhase},
     * and the cause's class name). The raw cause is <em>not</em> logged by the runner because
     * step-failure messages may embed config values (e.g. Jackson parse errors). The original cause
     * propagates unwrapped on the returned failed future; the caller is responsible for surfacing and
     * redacting it.
     *
     * @param <C> the application component type built by the factory
     * @param runtime the neutral runtime inputs (Vert.x instance + root config); must not be {@code
     *     null}
     * @param factory the factory that builds the application component from the runtime; must not be
     *     {@code null}
     * @return a future of a {@link VertiqueApplicationHandle} on full success, or a failed future
     *     carrying the original startup cause after teardown
     */
    public static <C extends VertiqueApplicationComponent> Future<VertiqueApplicationHandle<C>> start(
            VertiqueRuntime runtime, VertiqueComponentFactory<C> factory) {
        final C component;
        final List<ApplicationStartupStep> startupSteps;
        final List<ApplicationShutdownStep> shutdownSteps;
        try {
            component = factory.build(runtime);
            // Snapshot the lifecycle inputs ONCE, immediately after build, so an unscoped or dynamic
            // provider cannot change the step sets mid-startup (the phase chain reads the same partition
            // every iteration, and teardown runs against the same shutdown set). List.copyOf is an
            // immutable defensive copy. Snapshotting inside the same try block ensures that a Dagger
            // Provider that throws when the multibinding set is materialized is caught here and returned
            // as a failed future rather than escaping as a synchronous throw.
            startupSteps = List.copyOf(component.startupSteps());
            shutdownSteps = List.copyOf(component.shutdownSteps());
        } catch (Throwable t) {
            return Future.failedFuture(t);
        }

        // Mutable run state captured by the phase chain: completedStartupPhases records which phases'
        // startup work actually completed (so teardown runs shutdown steps only for those), reachedPhase
        // and runningUnit feed the failure diagnostic, and the verticle flag records whether any
        // verticle-subset phase was deployed (so teardown can skip undeploy when none was).
        RunState state = new RunState();

        Future<Void> chain = Future.succeededFuture();
        for (LifecyclePhase phase : LifecyclePhase.values()) {
            chain = chain.compose(v -> runPhase(component, startupSteps, phase, state));
        }

        return chain.map(v -> newHandle(component, runtime, shutdownSteps, state))
                .recover(cause -> {
                    // The raw cause is NOT passed as a SLF4J throwable arg: step-failure messages can
                    // embed config values (e.g. Jackson parse errors that include the offending JSON
                    // fragment), and logging them at ERROR level would leak those values into the log.
                    // The value-free failure-type hint (cause class name) is enough for triage; the
                    // full cause — with its original message and stack trace — propagates unwrapped on
                    // the returned failed future so the caller can surface it with its own redaction
                    // policy.
                    log.error(
                            "Application startup failed while running {} (phase {}); failure type: {}. "
                                    + "Tearing down; the original cause propagates on the returned future.",
                            state.runningUnit,
                            state.reachedPhase,
                            cause.getClass().getName());
                    return newHandle(component, runtime, shutdownSteps, state)
                            .teardown()
                            .transform(ignored -> Future.failedFuture(cause));
                });
    }

    // --- Private helpers ---

    /**
     * Builds a {@link VertiqueApplicationHandle} bound to the given component and runtime, capturing
     * the current run state (the set of completed startup phases + verticle-deployment flag) and the
     * shutdown-step snapshot. Called from both the success and failure branches of {@link #start} once
     * the phase chain has settled.
     *
     * @param <C> the application component type built by the factory
     * @param component the built application component
     * @param runtime the runtime the application was started from
     * @param shutdownSteps the immutable snapshot of shutdown steps taken once at start time
     * @param state the settled run state recording the completed startup phases and whether any
     *     verticle phase deployed
     * @return a handle bound to the started (or partially started) application
     */
    private static <C extends VertiqueApplicationComponent> VertiqueApplicationHandle<C> newHandle(
            C component, VertiqueRuntime runtime, List<ApplicationShutdownStep> shutdownSteps, RunState state) {
        // Defensive immutable copy of the completed-phase set so the handle cannot observe later
        // mutation of the runner's mutable EnumSet.
        return new VertiqueApplicationHandle<>(
                component,
                runtime,
                shutdownSteps,
                Set.copyOf(state.completedStartupPhases),
                state.anyVerticlePhaseDeployed);
    }

    /**
     * Runs one lifecycle phase: its startup steps sequentially, marks the phase as completed once all
     * its startup steps succeed (before the verticle deploy), then (for a verticle phase) its verticle
     * deployment.
     *
     * <p>The phase is recorded in {@link RunState#completedStartupPhases} <em>after</em> the phase's
     * startup-step chain completes but <em>before</em> the verticle deploy: a verticle-deploy failure
     * therefore still counts the phase's startup as complete (its steps did run), so that phase's
     * shutdown step runs during teardown. A failure within the startup-step chain skips the completion
     * mark, so that phase's shutdown step does not run.
     *
     * @param component the built application component
     * @param startupSteps the immutable snapshot of all startup steps taken once at start time
     * @param phase the phase to run
     * @param state the mutable run state tracking completed phases, the running unit, and verticle
     *     deployment
     * @return a future completing when the phase's steps and (if applicable) verticle deployment
     *     succeed; a failed future aborts the chain
     */
    private static Future<Void> runPhase(
            VertiqueApplicationComponent component,
            List<ApplicationStartupStep> startupSteps,
            LifecyclePhase phase,
            RunState state) {
        // reachedPhase feeds only the failure diagnostic — set at phase start; teardown bounds are
        // driven by completedStartupPhases instead (added once the phase's startup chain completes).
        state.reachedPhase = phase;

        List<ApplicationStartupStep> steps = startupSteps.stream()
                .filter(step -> step.phase() == phase)
                .sorted(LifecycleOrdered.comparator())
                .toList();

        Future<Void> chain = Future.succeededFuture();
        for (ApplicationStartupStep step : steps) {
            // Future.compose(mapper) invokes the mapper inside its own try/catch: a synchronous throw
            // from step.start() is converted to a failed future, exactly like an async failure. Either
            // way the chain fails here and flows into the recover() teardown branch, so the phase is
            // attributed correctly and teardown still runs — no extra guard is needed. Name the running
            // unit before running each step so the failure diagnostic identifies the failing step.
            chain = chain.compose(v -> {
                state.runningUnit = "startup step '" + step.orderKey() + "' ["
                        + step.getClass().getName() + "]";
                return step.start();
            });
        }

        // Mark the phase's startup as complete once all its steps succeed, before the verticle deploy.
        chain = chain.map(v -> {
            state.completedStartupPhases.add(phase);
            return null;
        });

        if (phase.isVerticlePhase()) {
            chain = chain.compose(v -> {
                state.runningUnit = "verticle deployment for phase " + phase;
                return component
                        .verticleDeploymentManager()
                        .deployPhase(phase)
                        .onSuccess(deployed -> state.anyVerticlePhaseDeployed = true);
            });
        }
        return chain;
    }

    /**
     * Mutable run state threaded through the phase chain. Not thread-safe by design: the phase chain
     * is a single sequential {@link Future} composition, so each mutation happens-before the next
     * phase reads it (the {@code compose} continuation observes the prior stage's writes).
     */
    private static final class RunState {

        /**
         * The phases whose startup steps all completed. A phase is added after its startup-step chain
         * succeeds and before its verticle deploy; teardown runs a phase's shutdown step only when its
         * phase is in this set.
         */
        private final Set<LifecyclePhase> completedStartupPhases = EnumSet.noneOf(LifecyclePhase.class);

        /** The furthest phase the startup sequence reached; seeded to the first phase. Diagnostic only. */
        private LifecyclePhase reachedPhase = LifecyclePhase.values()[0];

        /**
         * A human-readable identity of the unit currently running (a startup step's orderKey + FQN, or
         * a phase's verticle deployment), set before each unit runs so the failure diagnostic can name
         * the failing unit. Never holds config values — only step/verticle identity.
         */
        private String runningUnit = "no unit started";

        /** Whether any verticle-subset phase was deployed (gates teardown's verticle undeploy). */
        private boolean anyVerticlePhaseDeployed = false;
    }
}
