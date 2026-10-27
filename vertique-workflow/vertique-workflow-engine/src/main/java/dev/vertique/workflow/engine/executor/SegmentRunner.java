// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.DispositionResult;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.CommandSettled;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.TransientRetry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.SqlClient;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: runs the segment of one claimed command in one transaction, and disposes of what the transaction cannot
 * settle.
 *
 * <p>The segment tries the instance's execution lock, fences the claim, runs the command's work and completes the
 * command, all in the transaction the engine's transaction runner opens. The transaction is bounded by a deadline one
 * margin before the lease ends, measured from a monotonic reading taken before the claim was sent; the remaining
 * duration is passed on every attempt. A busy execution lock is retried until two margins before the lease ends, and
 * then the claim is released with its attempt restored, so the body starts only with at least one margin left.
 *
 * <p>A segment that fails rolls back and ends in one of these ways, decided from the failure as the runner returns it:
 *
 * <ul>
 *   <li>the segment hit its deadline: the same as a crashed one. Nothing commits, no disposition runs, the attempt
 *       stays spent and the claim lapses at the end of its lease; the failure is not logged as an error;</li>
 *   <li>a transient failure: the command is rescheduled with a capped backoff and the attempt its claim spent is
 *       restored, in one transaction that locks only the command row;</li>
 *   <li>any other failure, and a step-limit overrun: the command is failed, and for a start its instance, in a
 *       transaction of its own that tries the execution lock until one margin before the lease ends and then releases
 *       the claim with its attempt restored, because the failure recurs on the next claim.</li>
 * </ul>
 *
 * <p>A recorded {@code SIGNAL} settles itself inside its savepoint: the engine parks it, or rejects or stales it with a
 * reason, and answers with the matching outcome, so only an applied signal is completed here. A signal's deterministic
 * failure and its own step-limit overrun never reach the dispositions above. A recorded {@code COMPLETION} does the same:
 * the engine applies it under a savepoint and, when it is rejected, reopens the task it reserved and answers with the
 * matching outcome.
 *
 * <p>No executor connection ever waits on an execution lock.
 */
final class SegmentRunner {

    private static final Logger log = LoggerFactory.getLogger(SegmentRunner.class);

    /** The pause before the execution lock is tried again, in milliseconds. */
    private static final long BUSY_RETRY_MS = 100;

    /** The pause before a failed release is tried again, in milliseconds. */
    private static final long RELEASE_RETRY_MS = 200;

    /** How an attempt of the segment's transaction ended, as far as the runner is concerned. */
    private enum Step {
        COMPLETED,
        REJECTED,
        STALE,
        PARKED,
        BUSY,
        RELEASE,
        ENDED_WITHOUT_SETTLING
    }

    private final Vertx vertx;
    private final WorkflowExecutorConfig config;
    private final WorkflowCommandRepository<SqlClient> commands;
    private final WorkflowTransactionRunner<SqlClient> runner;
    private final WorkflowSegments segments;
    private final ExecutorEvents events;

    SegmentRunner(
            Vertx vertx,
            WorkflowExecutorConfig config,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowTransactionRunner<SqlClient> runner,
            WorkflowSegments segments,
            ExecutorEvents events) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.config = Objects.requireNonNull(config, "config");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.segments = Objects.requireNonNull(segments, "segments");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Runs the segment of a claimed command. The returned future never fails: every outcome is settled or logged here.
     *
     * @param head           the peeked command
     * @param claim          the claim
     * @param claimStartNanos the monotonic reading taken before the claim was sent
     * @return a future that completes when the segment is over
     */
    Future<Void> run(CommandHead head, ClaimedCommand claim, long claimStartNanos) {
        long lease = TimeUnit.SECONDS.toNanos(config.leaseSeconds());
        long margin = TimeUnit.MILLISECONDS.toNanos(config.marginMs());
        Instants instants = new Instants(
                claimStartNanos + lease, claimStartNanos + lease - 2 * margin, claimStartNanos + lease - margin);
        return attempt(head, claim, instants)
                .<Void>compose(step -> {
                    switch (step) {
                        case COMPLETED -> emitSettled(claim, "COMPLETE", claimStartNanos);
                        case REJECTED -> emitSettled(claim, "REJECTED", claimStartNanos);
                        case STALE -> emitSettled(claim, "STALE", claimStartNanos);
                        case PARKED -> emitSettled(claim, "PARKED", claimStartNanos);
                        default -> {
                            // released or abandoned: nothing was settled
                        }
                    }
                    return Future.succeededFuture();
                })
                .recover(failure -> dispose(claim, instants, failure, claimStartNanos));
    }

    /** The monotonic instants of one claim: the end of the lease, the busy-lock give-up and the segment deadline. */
    private record Instants(long leaseEnd, long giveUp, long deadline) {}

    private Future<Step> attempt(CommandHead head, ClaimedCommand claim, Instants instants) {
        long now = System.nanoTime();
        if (now - instants.giveUp() >= 0) {
            return release(claim, instants).map(Step.RELEASE);
        }
        long remaining = instants.deadline() - now;
        if (remaining <= 0) {
            log.debug("Command {} has no time left for its segment; the claim lapses", claim.commandId());
            return Future.succeededFuture(Step.ENDED_WITHOUT_SETTLING);
        }
        Future<Step> step;
        try {
            step = runner.inTransaction(null, Duration.ofNanos(remaining), tx -> body(head, claim, tx));
        } catch (RuntimeException e) {
            step = Future.failedFuture(e);
        }
        return step.compose(result -> switch (result) {
            case BUSY -> pause(BUSY_RETRY_MS).compose(ignored -> attempt(head, claim, instants));
            case RELEASE -> release(claim, instants).map(Step.RELEASE);
            default -> Future.succeededFuture(result);
        });
    }

    private Future<Step> body(CommandHead head, ClaimedCommand claim, SqlClient tx) {
        return commands.tryLockInstance(claim.workflowId(), tx).compose(locked -> {
            if (!locked) {
                return Future.succeededFuture(Step.BUSY);
            }
            return commands.fence(claim, tx).compose(fenced -> {
                if (fenced.isEmpty()) {
                    return Future.succeededFuture(Step.RELEASE);
                }
                return segments.run(head, claim, fenced.get(), tx, config.maxStepsPerSegment())
                        .compose(outcome -> switch (outcome) {
                            case APPLIED ->
                                settled(commands.complete(claim.commandId(), claim.token(), tx), Step.COMPLETED);
                            case STALE -> settled(commands.reject(claim.commandId(), claim.token(), tx), Step.REJECTED);
                            case PIN_MISMATCH -> Future.succeededFuture(Step.RELEASE);
                            case PARKED -> Future.succeededFuture(Step.PARKED);
                            case SETTLED_REJECTED -> Future.succeededFuture(Step.REJECTED);
                            case SETTLED_STALE -> Future.succeededFuture(Step.STALE);
                        });
            });
        });
    }

    /**
     * A settlement that updated no row means the claim no longer stands: fail so that nothing commits. The failure is
     * a lost claim, which the executor treats like a crash (nothing to write, the claim lapses), never as a failure of
     * the command.
     */
    private static Future<Step> settled(Future<Integer> update, Step step) {
        return update.compose(rows -> rows != null && rows > 0
                ? Future.succeededFuture(step)
                : Future.failedFuture(new ClaimLostException()));
    }

    private void emitSettled(ClaimedCommand claim, String outcome, long claimStartNanos) {
        events.emit(new CommandSettled(claim.kind(), outcome, Duration.ofNanos(System.nanoTime() - claimStartNanos)));
    }

    /** Disposes of a failed segment. The returned future never fails. */
    private Future<Void> dispose(ClaimedCommand claim, Instants instants, Throwable failure, long claimStartNanos) {
        if (Dispositions.isDeadline(failure)) {
            log.debug("Segment of command {} was not complete at its deadline and rolled back", claim.commandId());
            return Future.succeededFuture();
        }
        if (Dispositions.isClaimLost(failure)) {
            logFailure(
                    "lost its claim before its settlement and rolled back; the claim lapses with its lease",
                    claim,
                    failure);
            return Future.succeededFuture();
        }
        StepLimitExceededException limit = Dispositions.stepLimit(failure);
        if (limit != null) {
            logFailure("hit the step limit and rolled back; its command is failed", claim, failure);
            return fail(
                    claim,
                    CommandErrors.Reason.STEP_LIMIT,
                    CommandErrors.stepLimit(claim.kind(), limit.cap()),
                    instants,
                    claimStartNanos);
        }
        if (Dispositions.isTransient(failure)) {
            log.warn(
                    "Segment of command {} failed transiently ({}) and rolled back; its retry is scheduled",
                    claim.commandId(),
                    failure.getClass().getName());
            log.debug("Transient segment failure of command {}", claim.commandId(), failure);
            return reschedule(claim, instants);
        }
        logFailure("failed and rolled back; its command is failed", claim, failure);
        return fail(
                claim,
                CommandErrors.Reason.DETERMINISTIC_FAILURE,
                CommandErrors.deterministicFailure(claim.kind()),
                instants,
                claimStartNanos);
    }

    private void logFailure(String what, ClaimedCommand claim, Throwable failure) {
        log.error(
                "Segment of command {} {}: {}",
                claim.commandId(),
                what,
                failure.getClass().getName());
        log.debug("Segment failure of command {}", claim.commandId(), failure);
    }

    /**
     * Schedules the retry of a transient failure with a capped backoff and restores the attempt, trying again until the
     * lease has ended.
     */
    private Future<Void> reschedule(ClaimedCommand claim, Instants instants) {
        Future<Integer> rescheduled;
        try {
            rescheduled = Objects.requireNonNull(
                    commands.retryTransient(
                            claim,
                            count -> Dispositions.transientBackoffMs(
                                    count, config.transientBackoffBaseMs(), config.transientBackoffMaxMs()),
                            CommandErrors.transientFailure(claim.kind())),
                    "retryTransient returned null instead of a future");
        } catch (RuntimeException e) {
            rescheduled = Future.failedFuture(e);
        }
        return rescheduled.transform(result -> {
            if (result.succeeded()) {
                if (result.result() > 0) {
                    events.emit(new TransientRetry(claim.kind(), result.result()));
                }
                return Future.<Void>succeededFuture();
            }
            if (System.nanoTime() - instants.leaseEnd() < 0) {
                return pause(RELEASE_RETRY_MS).compose(ignored -> reschedule(claim, instants));
            }
            log.warn(
                    "Command {} could not be rescheduled before its lease ended: {}",
                    claim.commandId(),
                    result.cause().getClass().getName());
            return Future.<Void>succeededFuture();
        });
    }

    /**
     * Fails the command and, for a start, its instance in a transaction of its own that only tries the execution lock.
     * A busy lock is retried until one margin before the lease ends, then the claim is released with its attempt
     * restored: the failure recurs on the next claim.
     */
    private Future<Void> fail(
            ClaimedCommand claim,
            CommandErrors.Reason reason,
            String lastError,
            Instants instants,
            long claimStartNanos) {
        if (System.nanoTime() - instants.deadline() >= 0) {
            return release(claim, instants);
        }
        Future<DispositionResult> failed;
        try {
            failed = Objects.requireNonNull(
                    commands.failCommand(claim, reason.name(), lastError, segments::instanceFailed),
                    "failCommand returned null instead of a future");
        } catch (RuntimeException e) {
            failed = Future.failedFuture(e);
        }
        return failed.transform(result -> {
            if (result.succeeded()) {
                switch (result.result()) {
                    case APPLIED -> {
                        emitSettled(claim, "FAILED", claimStartNanos);
                        return Future.<Void>succeededFuture();
                    }
                    case CLAIM_LOST -> {
                        log.debug(
                                "The claim of command {} no longer stood; its failure was not written",
                                claim.commandId());
                        return Future.<Void>succeededFuture();
                    }
                    case BUSY -> {
                        // another transaction holds the execution lock: try again until the give-up
                    }
                }
            } else {
                log.debug("The failure of command {} could not be written", claim.commandId(), result.cause());
            }
            return pause(BUSY_RETRY_MS).compose(ignored -> fail(claim, reason, lastError, instants, claimStartNanos));
        });
    }

    /** Releases a claim whose segment did not run, and tries again until the lease has ended. */
    private Future<Void> release(ClaimedCommand claim, Instants instants) {
        Future<Boolean> released;
        try {
            released = Objects.requireNonNull(commands.release(claim), "release returned null instead of a future");
        } catch (RuntimeException e) {
            released = Future.failedFuture(e);
        }
        return released.<Void>mapEmpty().recover(failure -> {
            if (System.nanoTime() - instants.leaseEnd() < 0) {
                return pause(RELEASE_RETRY_MS).compose(ignored -> release(claim, instants));
            }
            log.warn(
                    "Command {} could not be released before its lease ended: {}",
                    claim.commandId(),
                    failure.getClass().getName());
            return Future.succeededFuture();
        });
    }

    private Future<Void> pause(long delayMs) {
        Promise<Void> promise = ((ContextInternal) vertx.getOrCreateContext()).promise();
        vertx.setTimer(delayMs, id -> promise.complete());
        return promise.future();
    }
}
