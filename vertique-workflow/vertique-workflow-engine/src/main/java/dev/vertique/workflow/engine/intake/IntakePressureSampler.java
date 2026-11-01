// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.intake;

import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.workflow.config.WorkflowIntakeConfig;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.SqlClient;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: the verticle that feeds a node's {@link IntakeAdmissionState} with a reading of the due commands.
 *
 * <p>Every node deploys one instance, whether or not it runs an executor, because a node that only records starts and
 * signals needs the reading as much as one that runs them. The sampler shares nothing with the executor: it reads
 * through the command repository's {@link WorkflowCommandRepository#countDue bounded count} and moves the state it is
 * given.
 *
 * <p>The count stops at one above the high mark, so a reading costs at most that many index entries plus the commands
 * claimed at the time. At most one read is outstanding. A read that has not settled when its deadline, one sample
 * interval, passes counts as a failed sample; no new read starts until it settles, and each further interval it stays
 * outstanding counts as one more failed sample. The result of a read that settles after its deadline, or after the
 * sampler stopped, is discarded.
 *
 * <p>The state of the sampler, and the timers that move it, belong to the verticle's own context; a read that settles on
 * another context hops back before it is applied. It runs on the event loop and makes no blocking call.
 */
public final class IntakePressureSampler extends AbstractVerticle {

    /** The name of the deployment. */
    public static final String DEPLOYMENT_NAME = "workflow-intake-sampler";

    /** The priority of the deployment within the {@code SERVICES} phase. */
    public static final int DEPLOYMENT_PRIORITY = 100;

    /** How many sample intervals an idle queue waits between two reads. */
    static final int RELAXED_FACTOR = 4;

    private static final Logger log = LoggerFactory.getLogger(IntakePressureSampler.class);

    private final WorkflowCommandRepository<SqlClient> commands;
    private final WorkflowIntakeConfig config;
    private final IntakeAdmissionState state;
    private final List<WorkflowExecutorObserver> observers;

    private Context root;
    private boolean stopped;
    private boolean reading;
    private long readNumber;
    private long tickTimerId = -1;
    private long deadlineTimerId = -1;

    /**
     * Creates the sampler of one node.
     *
     * @param commands the command repository the reading is taken from
     * @param config the intake configuration; its sample interval paces the reads
     * @param state the node's admission state the readings move
     * @param observers the observers told of each transition between admitting and refusing; may be empty
     */
    public IntakePressureSampler(
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowIntakeConfig config,
            IntakeAdmissionState state,
            Set<WorkflowExecutorObserver> observers) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.config = Objects.requireNonNull(config, "config");
        this.state = Objects.requireNonNull(state, "state");
        this.observers = List.copyOf(Objects.requireNonNull(observers, "observers"));
    }

    /**
     * The deployment: {@code workflow-intake-sampler} in the {@code SERVICES} phase with a single instance on the
     * event loop.
     *
     * @param commands the command repository the reading is taken from
     * @param config the intake configuration
     * @param state the node's admission state
     * @param observers the observers told of each transition
     * @return the deployment
     */
    public static VerticleDeployment deployment(
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowIntakeConfig config,
            IntakeAdmissionState state,
            Set<WorkflowExecutorObserver> observers) {
        return new VerticleDeployment(
                DEPLOYMENT_NAME,
                () -> new IntakePressureSampler(commands, config, state, observers),
                new DeploymentOptions().setInstances(1),
                LifecyclePhase.SERVICES,
                DEPLOYMENT_PRIORITY);
    }

    @Override
    public void start(Promise<Void> startPromise) {
        root = context;
        tick();
        startPromise.complete();
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        stopped = true;
        cancel(tickTimerId);
        cancel(deadlineTimerId);
        stopPromise.complete();
    }

    /**
     * One step of the loop: starts a read, or, when the previous read is still outstanding, counts another failed
     * sample.
     */
    private void tick() {
        tickTimerId = -1;
        if (stopped) {
            return;
        }
        if (reading) {
            // The previous read has outlived its deadline and has not settled; no second read starts behind it.
            applyFailure();
            scheduleTick(config.sampleIntervalMs());
            return;
        }
        reading = true;
        long number = ++readNumber;
        // The deadline of the read: a raw one-shot timer bound to this read, which lets a hung read count as a failed
        // sample instead of leaving the node on its old state without end.
        deadlineTimerId = vertx.setTimer(
                config.sampleIntervalMs(),
                id -> onContext(() -> {
                    if (!stopped && number == readNumber && reading) {
                        deadlineTimerId = -1;
                        applyFailure();
                        scheduleTick(config.sampleIntervalMs());
                    }
                }));
        Future<Long> read;
        try {
            read = Objects.requireNonNull(
                    commands.countDue(config.highMark() + 1L), "countDue returned null instead of a future");
        } catch (Exception | LinkageError | AssertionError e) {
            read = Future.failedFuture(e);
        }
        read.onComplete(
                result -> onContext(() -> settle(number, result.succeeded() ? result.result() : null, result.cause())));
    }

    /**
     * Applies the outcome of a read that settled; a read that settled after its deadline is only forgotten, and so is
     * one that settled after the sampler stopped, because the state it would move is shared with a sampler deployed
     * later.
     */
    private void settle(long number, Long due, Throwable failure) {
        if (stopped || number != readNumber || !reading) {
            return;
        }
        reading = false;
        boolean late = deadlineTimerId == -1;
        cancel(deadlineTimerId);
        deadlineTimerId = -1;
        if (late) {
            // Its deadline already counted this sample as failed, and the tick that follows starts a new read.
            return;
        }
        if (due == null) {
            log.debug("The intake pressure could not be read", failure);
            applyFailure();
            scheduleTick(config.sampleIntervalMs());
            return;
        }
        IntakeAdmissionState.Transition transition = state.onReading(due);
        if (transition != null) {
            report(transition);
        }
        // The next delay depends on the reading: an idle queue is read less often, which keeps the cost of an idle
        // cluster down. This is why the loop is an adaptive one-shot timer and not a fixed period.
        boolean idle = state.lastDue() <= config.lowMark();
        scheduleTick(idle ? config.sampleIntervalMs() * RELAXED_FACTOR : config.sampleIntervalMs());
    }

    private void applyFailure() {
        IntakeAdmissionState.Transition transition = state.onReadFailed();
        if (transition != null) {
            report(transition);
        } else if (state.outageReached()) {
            log.warn(
                    "Workflow intake pressure could not be read for {} samples in a row; this node admits starts and"
                            + " signals until a read succeeds",
                    IntakeAdmissionState.TOLERATED_FAILED_SAMPLES + 1);
        }
    }

    /** Logs one transition and tells the observers. */
    private void report(IntakeAdmissionState.Transition transition) {
        if (transition.readFailed()) {
            log.warn(
                    "Workflow intake pressure could not be read for {} samples in a row; this node admits starts and"
                            + " signals again until a read succeeds",
                    IntakeAdmissionState.TOLERATED_FAILED_SAMPLES + 1);
        } else if (transition.refusing()) {
            log.warn(
                    "Workflow intake is refused: {} commands are due, at or above the high mark of {}",
                    transition.due(),
                    config.highMark());
        } else {
            log.warn(
                    "Workflow intake is admitted again: {} commands are due, at or below the low mark of {}",
                    transition.due(),
                    config.lowMark());
        }
        if (observers.isEmpty()) {
            return;
        }
        WorkflowExecutorEvent event = new WorkflowExecutorEvent.IntakePressureChanged(
                transition.refusing(), transition.due(), transition.readFailed());
        for (WorkflowExecutorObserver observer : observers) {
            try {
                observer.onEvent(event);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError error) {
                    throw error;
                }
                log.warn(
                        "Executor observer {} threw while handling a {} event: {}",
                        observer.getClass().getName(),
                        event.getClass().getSimpleName(),
                        t.getClass().getName());
                log.debug("Executor observer failure", t);
            }
        }
    }

    private void scheduleTick(long delayMs) {
        if (stopped) {
            return;
        }
        tickTimerId = vertx.setTimer(delayMs, id -> onContext(this::tick));
    }

    private void cancel(long timerId) {
        if (timerId != -1) {
            vertx.cancelTimer(timerId);
        }
    }

    /** Runs the action on the sampler's own context, directly when the caller is already on it. */
    private void onContext(Runnable action) {
        Context own = root;
        if (own == null || own == Vertx.currentContext()) {
            action.run();
        } else {
            own.runOnContext(ignored -> action.run());
        }
    }
}
