// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps this node's definition registry in step with the durable definition table by reconciling it periodically.
 *
 * <p>Every {@code workflow.definitions.maxStalenessSeconds} (thirty seconds when nothing is configured) the poller
 * runs one reconciliation pass: it reads the table's metadata and applies every row whose state differs from what
 * this node holds, so that a lifecycle change made on another node reaches this one. The cadence plus the time
 * the pass needs to apply is the only bound on how long a change takes to arrive. A row that cannot be applied is
 * counted by {@link WorkflowDefinitionRegistryView#applyFailures()} and retried by the next pass.
 *
 * <p>The same pass refreshes this node's activation view from the committed activation records, after the
 * definition rows, so an activation, {@code follow} or {@code deactivateDefault} committed on another node changes
 * which version a start selects here within the same bound. A start never reads the activation table.
 *
 * <p>At most one pass runs at a time. A trigger that arrives while a pass runs, from the timer or from
 * {@link #pollNow()}, joins that pass.
 *
 * <p>The timer is armed after every verticle has deployed, so the first pass runs one period after startup; the
 * startup reconciliation has already applied the table by then. On shutdown the timer is cancelled at once and
 * shutdown completes after the running pass has finished.
 */
@Singleton
public final class WorkflowDefinitionPropagationPoller {
    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionPropagationPoller.class.getName());

    /** One reconciliation pass; the poller serializes the calls. */
    @FunctionalInterface
    interface Pass {
        Future<Void> run();
    }

    private final Vertx vertx;
    private final WorkflowDefinitionsConfig config;
    private final Pass pass;
    private final Object lock = new Object();
    private Future<Void> inFlight;
    private boolean stopped;
    private boolean armed;
    private long timerId;

    @Inject
    WorkflowDefinitionPropagationPoller(
            WorkflowDefinitionRegistryStore store,
            Vertx vertx,
            Optional<WorkflowDefinitionsConfig> config,
            DefinitionPropagationPass pass) {
        this(store, vertx, config, pass::run);
    }

    WorkflowDefinitionPropagationPoller(
            WorkflowDefinitionRegistryStore store, Vertx vertx, Optional<WorkflowDefinitionsConfig> config, Pass pass) {
        Objects.requireNonNull(store, "store");
        this.vertx = vertx;
        this.config = config.orElseGet(WorkflowDefinitionsConfig::defaults);
        this.pass = pass;
    }

    /**
     * Reconciles this node's registry with the durable table now.
     *
     * <p>The call joins the pass that is running, if any, and returns that pass's future. The future succeeds
     * when every row was handled, including when some rows failed to apply; those are reflected in
     * {@link WorkflowDefinitionRegistryView#applyFailures()}. It fails only when the table could not be read.
     * After shutdown has begun, the returned future is already complete and no pass runs. A caller that joins a
     * running pass receives that pass, which may have read the table before the caller's own change was committed;
     * such a change can need one more pass.
     *
     * @return a future that completes when the pass has finished
     */
    public Future<Void> pollNow() {
        Promise<Void> started = Promise.promise();
        Future<Void> running;
        synchronized (lock) {
            if (stopped) return Future.succeededFuture();
            if (inFlight != null) return inFlight;
            running = started.future();
            inFlight = running;
        }
        Future<Void> work;
        try {
            work = pass.run();
        } catch (Throwable failure) {
            work = Future.failedFuture(failure);
        }
        work.onComplete(outcome -> {
            synchronized (lock) {
                inFlight = null;
            }
            started.handle(outcome);
        });
        return running;
    }

    /** The step that arms the timer once every verticle has deployed. */
    ApplicationStartupStep startupStep() {
        return new StartupStep();
    }

    /** The step that cancels the timer and waits for the running pass. */
    ApplicationShutdownStep shutdownStep() {
        return new ShutdownStep();
    }

    private void tick() {
        pollNow()
                .onFailure(failure -> log.warn(
                        "Reconciling the definition registry with the durable table failed: {}; the next pass retries",
                        failure.getClass().getSimpleName()));
    }

    private final class StartupStep implements ApplicationStartupStep {

        @Override
        public LifecyclePhase phase() {
            return LifecyclePhase.AFTER_START;
        }

        @Override
        public Future<Void> start() {
            long periodMs;
            try {
                periodMs = Math.multiplyExact(config.maxStalenessSeconds(), 1000L);
            } catch (ArithmeticException overflow) {
                return Future.failedFuture(
                        new ConfigurationException("workflow.definitions.maxStalenessSeconds is too large: "
                                + config.maxStalenessSeconds() + " seconds do not fit in milliseconds"));
            }
            synchronized (lock) {
                if (stopped || armed) return Future.succeededFuture();
                timerId = vertx.setPeriodic(periodMs, ignored -> tick());
                armed = true;
            }
            return Future.succeededFuture();
        }
    }

    private final class ShutdownStep implements ApplicationShutdownStep {

        @Override
        public LifecyclePhase phase() {
            // Shutdown runs in reverse order, so this step stops the poller before the infrastructure it reads.
            return LifecyclePhase.AFTER_START;
        }

        @Override
        public Future<Void> stop() {
            Future<Void> running;
            synchronized (lock) {
                stopped = true;
                if (armed) {
                    vertx.cancelTimer(timerId);
                    armed = false;
                }
                running = inFlight;
            }
            if (running == null) return Future.succeededFuture();
            Promise<Void> drained = Promise.promise();
            running.onComplete(ignored -> drained.complete());
            return drained.future();
        }
    }
}
