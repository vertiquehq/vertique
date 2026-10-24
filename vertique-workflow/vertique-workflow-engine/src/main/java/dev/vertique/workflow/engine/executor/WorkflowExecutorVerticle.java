// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.spi.CommandWakeups;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.SqlClient;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: one instance of the workflow executor, deployed like the outbox relay on the event loop.
 *
 * <p>Each instance owns one peek, claim and segment loop. Its root context is its own verticle context, captured when
 * it starts and never the context of whoever deployed it; every segment runs on a fresh duplicate of that root, so
 * nothing bound to the context of the deployer, of a recording caller or of another segment reaches a segment.
 * Instances share the engine's singletons and coordinate only through PostgreSQL. The verticle runs on the event
 * loop only: it uses no worker threading and makes no blocking call.
 *
 * <p>The instance registers with the wake-up source when it starts and closes the registration when it stops; a
 * wake-up runs on the instance's own context and only asks for a pass.
 *
 * <p>There is no supervision and no liveness check: work held by an instance that died is recovered when its lease
 * expires.
 */
public final class WorkflowExecutorVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutorVerticle.class);

    private final WorkflowExecutorConfig config;
    private final WorkflowCommandRepository<SqlClient> commands;
    private final WorkflowTransactionRunner<SqlClient> runner;
    private final WorkflowSegments segments;
    private final String instanceId;
    private final Set<WorkflowExecutorObserver> observers;

    private final CommandWakeups wakeups;

    private ExecutorLoop loop;
    private CommandWakeups.Registration registration;

    /**
     * Creates one executor instance.
     *
     * @param config     the executor configuration
     * @param commands   the command repository
     * @param runner     the transaction runner segments run through
     * @param segments   the engine's side of a segment
     * @param instanceId the id this instance claims commands as
     */
    public WorkflowExecutorVerticle(
            WorkflowExecutorConfig config,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowTransactionRunner<SqlClient> runner,
            WorkflowSegments segments,
            String instanceId) {
        this(config, commands, runner, segments, instanceId, Set.of());
    }

    /**
     * Creates one executor instance that reports its events to observers.
     *
     * @param config     the executor configuration
     * @param commands   the command repository
     * @param runner     the transaction runner segments run through
     * @param segments   the engine's side of a segment
     * @param instanceId the id this instance claims commands as
     * @param observers  the observers of the executor's events; may be empty
     */
    public WorkflowExecutorVerticle(
            WorkflowExecutorConfig config,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowTransactionRunner<SqlClient> runner,
            WorkflowSegments segments,
            String instanceId,
            Set<WorkflowExecutorObserver> observers) {
        this(config, commands, runner, segments, instanceId, observers, CommandWakeups.none());
    }

    /**
     * Creates one executor instance that reports its events to observers and is woken by a command source.
     *
     * @param config     the executor configuration
     * @param commands   the command repository
     * @param runner     the transaction runner segments run through
     * @param segments   the engine's side of a segment
     * @param instanceId the id this instance claims commands as
     * @param observers  the observers of the executor's events; may be empty
     * @param wakeups    the source that wakes the instance when a command becomes pending or deferred
     */
    public WorkflowExecutorVerticle(
            WorkflowExecutorConfig config,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowTransactionRunner<SqlClient> runner,
            WorkflowSegments segments,
            String instanceId,
            Set<WorkflowExecutorObserver> observers,
            CommandWakeups wakeups) {
        this.wakeups = Objects.requireNonNull(wakeups, "wakeups");
        this.observers = Set.copyOf(observers);
        this.config = Objects.requireNonNull(config, "config");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.segments = Objects.requireNonNull(segments, "segments");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    @Override
    public void start(Promise<Void> startPromise) {
        ContextInternal root = (ContextInternal) context;
        ExecutorEvents events = ExecutorEvents.to(observers);
        loop = new ExecutorLoop(
                vertx,
                root,
                config,
                instanceId,
                new CommandPeek(commands, segments),
                commands,
                new SegmentRunner(vertx, config, commands, runner, segments, events),
                segments,
                events,
                new StuckReporter(commands, config.stuckAfterSeconds(), events));
        loop.start();
        try {
            registration = wakeups.register(root, loop::wake);
        } catch (RuntimeException e) {
            // the instance polls alone
            log.warn(
                    "Workflow executor {} could not register for wake-ups: {}",
                    instanceId,
                    e.getClass().getName());
        }
        log.info("Workflow executor {} started", instanceId);
        startPromise.complete();
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (loop == null) {
            stopPromise.complete();
            return;
        }
        if (registration != null) {
            registration.close();
            registration = null;
        }
        loop.stop().onComplete(result -> {
            log.info("Workflow executor {} stopped", instanceId);
            stopPromise.complete();
        });
    }
}
