// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.db.exception.TransactionDeadlineExceededException;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
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
 * INTERNAL: runs the segment of one claimed command in one transaction, and settles what the transaction cannot.
 *
 * <p>The segment tries the instance's execution lock, fences the claim, runs the command's work and completes the
 * command, all in the transaction the engine's transaction runner opens. The transaction is bounded by a deadline one
 * margin before the lease ends, measured from a monotonic reading taken before the claim was sent; the remaining
 * duration is passed on every attempt. A busy execution lock is retried until two margins before the lease ends, and
 * then the claim is released with its attempt restored, so the body starts only with at least one margin left.
 *
 * <p>A segment that hits its deadline is the same as a crashed one: nothing commits, no release runs, the attempt
 * stays spent and the claim lapses at the end of its lease. Any other failure rolls the segment back and leaves the
 * claim to lapse in the same way.
 */
final class SegmentRunner {

    private static final Logger log = LoggerFactory.getLogger(SegmentRunner.class);

    /** The pause before the execution lock is tried again, in milliseconds. */
    private static final long BUSY_RETRY_MS = 100;

    /** The pause before a failed release is tried again, in milliseconds. */
    private static final long RELEASE_RETRY_MS = 200;

    /** How an attempt of the segment's transaction ended, as far as the runner is concerned. */
    private enum Step {
        DONE,
        BUSY,
        RELEASE
    }

    private final Vertx vertx;
    private final WorkflowExecutorConfig config;
    private final WorkflowCommandRepository<SqlClient> commands;
    private final WorkflowTransactionRunner<SqlClient> runner;
    private final WorkflowSegments segments;

    SegmentRunner(
            Vertx vertx,
            WorkflowExecutorConfig config,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowTransactionRunner<SqlClient> runner,
            WorkflowSegments segments) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.config = Objects.requireNonNull(config, "config");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.segments = Objects.requireNonNull(segments, "segments");
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
        return attempt(head, claim, instants).recover(failure -> {
            settleFailure(claim, failure);
            return Future.succeededFuture();
        });
    }

    /** The monotonic instants of one claim: the end of the lease, the busy-lock give-up and the segment deadline. */
    private record Instants(long leaseEnd, long giveUp, long deadline) {}

    private Future<Void> attempt(CommandHead head, ClaimedCommand claim, Instants instants) {
        long now = System.nanoTime();
        if (now - instants.giveUp() >= 0) {
            return release(claim, instants);
        }
        long remaining = instants.deadline() - now;
        if (remaining <= 0) {
            log.debug("Command {} has no time left for its segment; the claim lapses", claim.commandId());
            return Future.succeededFuture();
        }
        Future<Step> step;
        try {
            step = runner.inTransaction(null, Duration.ofNanos(remaining), tx -> body(head, claim, tx));
        } catch (RuntimeException e) {
            step = Future.failedFuture(e);
        }
        return step.compose(result -> switch (result) {
            case DONE -> Future.<Void>succeededFuture();
            case BUSY -> pause(BUSY_RETRY_MS).compose(ignored -> attempt(head, claim, instants));
            case RELEASE -> release(claim, instants);
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
                return segments.run(head, claim, fenced.get(), tx).compose(outcome -> switch (outcome) {
                    case APPLIED -> settled(commands.complete(claim.commandId(), claim.token(), tx));
                    case STALE -> settled(commands.reject(claim.commandId(), claim.token(), tx));
                    case PIN_MISMATCH -> Future.succeededFuture(Step.RELEASE);
                });
            });
        });
    }

    /** A settlement that updated no row means the claim no longer stands: fail so that nothing commits. */
    private static Future<Step> settled(Future<Integer> update) {
        return update.compose(rows -> rows != null && rows > 0
                ? Future.succeededFuture(Step.DONE)
                : Future.failedFuture(new IllegalStateException("the command's settlement updated no row")));
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

    private void settleFailure(ClaimedCommand claim, Throwable failure) {
        if (isDeadline(failure)) {
            log.debug("Segment of command {} was not complete at its deadline and rolled back", claim.commandId());
            return;
        }
        log.error(
                "Segment of command {} failed and rolled back; its claim lapses with its lease: {}",
                claim.commandId(),
                failure.getClass().getName());
        log.debug("Segment failure of command {}", claim.commandId(), failure);
    }

    private static boolean isDeadline(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++) {
            if (cause instanceof TransactionDeadlineExceededException) {
                return true;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return false;
    }

    private Future<Void> pause(long delayMs) {
        Promise<Void> promise = ((ContextInternal) vertx.getOrCreateContext()).promise();
        vertx.setTimer(delayMs, id -> promise.complete());
        return promise.future();
    }
}
