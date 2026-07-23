// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application;

import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handle to a started Vertique application, returned by {@link VertiqueApplicationBootstrap#start}.
 *
 * <p>The handle exposes the built {@link VertiqueApplicationComponent} and the {@link
 * VertiqueRuntime} it was started from, plus an idempotent {@link #shutdown()} that tears the
 * application down in the reverse of its startup order.
 *
 * <p><strong>Vertx is host-owned.</strong> Teardown undeploys verticles and runs shutdown steps but
 * never closes the {@code Vertx} instance — the standalone launcher (or an embedding host) owns the
 * {@code Vertx} lifecycle.
 *
 * <p>Instances are created by {@link VertiqueApplicationBootstrap}; the package-private constructor
 * captures the immutable snapshot of shutdown steps, the set of {@link LifecyclePhase phases whose
 * startup completed} — which selects the shutdown steps that run — and whether any verticles were
 * deployed.
 *
 * @param <C> the application component type the handle exposes
 */
public final class VertiqueApplicationHandle<C extends VertiqueApplicationComponent> {

    private static final Logger log = LoggerFactory.getLogger(VertiqueApplicationHandle.class);

    private final C component;
    private final VertiqueRuntime runtime;
    private final List<ApplicationShutdownStep> shutdownSteps;
    private final Set<LifecyclePhase> completedStartupPhases;
    private final boolean anyVerticlePhaseDeployed;

    /** Memoized teardown future, guarded by {@code this}: the single future all callers observe. */
    private Future<Void> shutdownFuture;

    /**
     * Creates a handle bound to the started application.
     *
     * @param component the built application component; must not be {@code null}
     * @param runtime the runtime the application was started from; must not be {@code null}
     * @param shutdownSteps the immutable snapshot of shutdown steps taken once at start time; must not
     *     be {@code null}
     * @param completedStartupPhases the (immutable) set of phases whose startup steps all completed;
     *     teardown runs a shutdown step only when its phase is in this set
     * @param anyVerticlePhaseDeployed whether any verticle-subset phase was deployed during startup;
     *     when {@code false}, teardown skips verticle undeploy entirely
     */
    VertiqueApplicationHandle(
            C component,
            VertiqueRuntime runtime,
            List<ApplicationShutdownStep> shutdownSteps,
            Set<LifecyclePhase> completedStartupPhases,
            boolean anyVerticlePhaseDeployed) {
        this.component = component;
        this.runtime = runtime;
        this.shutdownSteps = shutdownSteps;
        this.completedStartupPhases = completedStartupPhases;
        this.anyVerticlePhaseDeployed = anyVerticlePhaseDeployed;
    }

    // --- Accessors ---

    /**
     * Returns the built application component.
     *
     * @return the component; never {@code null}
     */
    public C component() {
        return component;
    }

    /**
     * Returns the runtime this application was started from.
     *
     * @return the runtime; never {@code null}
     */
    public VertiqueRuntime runtime() {
        return runtime;
    }

    // --- Shutdown ---

    /**
     * Shuts the application down, idempotently.
     *
     * <p>The first call runs {@link #teardown()} — shutdown steps in reverse lifecycle order followed
     * by verticle undeploy — and <em>memoizes</em> the resulting future. Every subsequent (and every
     * concurrent) call returns that same {@code Future<Void>}, so a second caller observes success
     * only when teardown has actually settled, never while it is still running. The {@code Vertx}
     * instance is never closed (host-owned).
     *
     * @return the single teardown future shared by all callers
     */
    public synchronized Future<Void> shutdown() {
        if (shutdownFuture == null) {
            shutdownFuture = teardown();
        }
        return shutdownFuture;
    }

    /**
     * Runs the reverse-order teardown shared by the failure path and {@link #shutdown()}.
     *
     * <p>First runs the {@link ApplicationShutdownStep}s whose {@link LifecyclePhase} is in {@link
     * #completedStartupPhases} (i.e. that phase's startup completed), in <em>reverse</em> {@link
     * LifecycleOrdered#comparator()} order, each best-effort (a failing {@code stop()} is logged and
     * swallowed so it never masks an original startup cause). Then, if any verticle phase was
     * deployed, undeploys the generic verticles in reverse phase order via {@link
     * dev.vertique.deploy.VerticleDeploymentManager#undeployAll()}.
     *
     * @return a future that succeeds once all teardown actions have settled
     */
    Future<Void> teardown() {
        List<ApplicationShutdownStep> reverseSteps = shutdownSteps.stream()
                .filter(step -> completedStartupPhases.contains(step.phase()))
                .sorted(LifecycleOrdered.comparator().reversed())
                .toList();

        Future<Void> chain = Future.succeededFuture();
        for (ApplicationShutdownStep step : reverseSteps) {
            chain = chain.compose(v -> runShutdownStep(step));
        }
        if (anyVerticlePhaseDeployed) {
            chain = chain.compose(v -> component.verticleDeploymentManager().undeployAll());
        }
        return chain;
    }

    // --- Private helpers ---

    /**
     * Runs a single shutdown step best-effort: a failure (or a thrown exception) is logged and
     * swallowed so it never masks a startup cause or aborts the rest of teardown.
     *
     * @param step the shutdown step to run
     * @return a future that always succeeds once the step has settled
     */
    private static Future<Void> runShutdownStep(ApplicationShutdownStep step) {
        Future<Void> result;
        try {
            result = step.stop();
        } catch (Throwable t) {
            log.warn("Shutdown step {} threw; ignoring", step.orderKey(), t);
            return Future.succeededFuture();
        }
        return result.recover(cause -> {
            log.warn("Shutdown step {} failed; ignoring", step.orderKey(), cause);
            return Future.succeededFuture();
        });
    }
}
