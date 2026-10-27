// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.engine.spi.ParkedSignal;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: applies the parked signals of an instance at the waits a segment has reached.
 *
 * <p>A signal the instance does not await when it executes is parked and keeps its place in the queue. When a segment
 * has driven the instance to a wait, {@link #drain} applies the parked signals that wait accepts, in queue order, each
 * as an operation of its own command under a savepoint, and settles each one in the segment's transaction without a
 * claim token:
 *
 * <ul>
 *   <li>a signal that is deadline-blocked at the wait stays parked;</li>
 *   <li>a signal whose application fails in a way a retry repeats is rolled back to its savepoint and rejected, and the
 *       next one is tried;</li>
 *   <li>before each signal the segment's step count is checked; a signal is applied only while the count is at most half
 *       of the step cap. A signal that would start beyond that budget, and one whose application overruns the cap inside
 *       its savepoint, is not rejected: it is set back to {@code PENDING} with every other signal the wait accepts, at its
 *       original position, and the segment commits at the wait. Each of them then runs as the executing command of a
 *       segment of its own;</li>
 *   <li>any other failure rolls back the whole segment, and the signal stays parked.</li>
 * </ul>
 *
 * <p>When the instance has ended, its parked and dead-lettered commands are settled together, in one bulk write that
 * frees the open-signal slot of each parked signal: rejected, or cancelled when the instance was cancelled.
 *
 * <p>Writes that settle a command, and the history entries of its outcome, are made at the level of the segment's
 * transaction, never inside a savepoint, so that they belong to the segment's transaction and survive the rollback of a
 * sibling signal.
 */
public final class ParkedSignals {

    private static final Logger log = LoggerFactory.getLogger(ParkedSignals.class);

    /** A signal wait the instance, or one of its branches, is in. */
    public record WaitAddress(
            String stepId,
            String signalName,
            @Nullable String forkStepId,
            @Nullable String branchId) {}

    /**
     * The state of an instance after a drive.
     *
     * @param endedStatus the status of the instance when it has ended, or {@code null}
     * @param open        the signal waits the instance and its branches are in, the instance's first
     */
    public record Waits(@Nullable String endedStatus, List<WaitAddress> open) {}

    /** The engine side of parked signals: applying a signal and writing the history of its outcome. */
    public interface Host {

        /**
         * Reads the state of the instance after a drive.
         *
         * @param instance the instance
         * @param tx       the segment's transaction handle
         * @return the waits the instance holds, or its ended status
         */
        Future<Waits> waits(WorkflowInstanceId instance, SqlClient tx);

        /**
         * Applies a parked signal to the instance, which is at a wait the signal's name and address match. Runs inside
         * the savepoint of the signal; a failed future rolls the signal back to it.
         *
         * @param signal the parked command
         * @param tx     the segment's transaction handle
         * @return a future that completes when the signal is applied and the instance is driven to its next wait
         */
        Future<Void> apply(SegmentCommand signal, SqlClient tx);

        /**
         * Writes the history of a parked signal that was applied.
         *
         * @param signal the command
         * @param tx     the segment's transaction handle
         * @return a future that completes when the entries are written
         */
        Future<Void> applied(SegmentCommand signal, SqlClient tx);

        /**
         * Writes the history of a parked signal that was rejected.
         *
         * @param instance   the instance
         * @param commandId  the command's row id
         * @param signalName the signal's name
         * @param reason     the sanitized reason
         * @param tx         the segment's transaction handle
         * @return a future that completes when the entry is written
         */
        Future<Void> rejected(
                WorkflowInstanceId instance, long commandId, String signalName, String reason, SqlClient tx);

        /**
         * The nodes the segment on this transaction has executed so far.
         *
         * @param tx the segment's transaction handle
         * @return the count, {@code 0} when the segment is not capped
         */
        int stepsUsed(SqlClient tx);

        /**
         * The most nodes the segment on this transaction may execute.
         *
         * @param tx the segment's transaction handle
         * @return the cap, {@link Integer#MAX_VALUE} when the segment is not capped
         */
        int stepCap(SqlClient tx);
    }

    private final WorkflowCommandRepository<SqlClient> commands;
    private final DeadlineGuard guard;
    private final Host host;

    /**
     * The transactions in which a caller drains the parked signals itself, by identity: a segment, and a drain that is
     * running. A wait entered in such a transaction does not drain again.
     */
    private final Set<SqlClient> managed =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    /**
     * Creates the parked-signal application over the command repository.
     *
     * @param commands the command repository
     * @param guard    the deadline guard
     * @param host     the engine side
     */
    public ParkedSignals(WorkflowCommandRepository<SqlClient> commands, DeadlineGuard guard, Host host) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Applies the parked signals that the waits the instance has reached accept, or rejects them when it has ended.
     *
     * @param instance the instance a segment has just driven
     * @param tx       the segment's transaction handle
     * @return a future that completes when the segment may commit
     */
    public Future<Void> drain(WorkflowInstanceId instance, SqlClient tx) {
        return managing(tx, () -> drainManaged(instance, tx));
    }

    /**
     * Runs work in a transaction whose caller drains the parked signals itself: a wait the work enters does not drain,
     * so the application of a parked signal never nests inside the savepoint of the command that is executing.
     *
     * @param tx   the transaction handle
     * @param work the work
     * @param <T>  the result type of the work
     * @return the future of the work
     */
    public <T> Future<T> managing(SqlClient tx, Supplier<Future<T>> work) {
        boolean added = managed.add(tx);
        Future<T> running;
        try {
            running = work.get();
        } catch (RuntimeException e) {
            if (added) {
                managed.remove(tx);
            }
            throw e;
        }
        return added
                ? running.eventually(() -> {
                    managed.remove(tx);
                    return Future.succeededFuture();
                })
                : running;
    }

    /**
     * Drains the parked signals of an instance whose drive has just reached its waits on a path that no segment owns
     * (a task completion, a timer fire, a retry, a migration), unless the transaction's caller drains itself.
     *
     * @param instance the instance a drive has just moved
     * @param tx       the transaction handle
     * @return a future that completes when the parked signals the reached waits accept are applied
     */
    public Future<Void> afterDrive(WorkflowInstanceId instance, SqlClient tx) {
        return managed.contains(tx) ? Future.succeededFuture() : drain(instance, tx);
    }

    private Future<Void> drainManaged(WorkflowInstanceId instance, SqlClient tx) {
        return commands.parkedSignals(instance, tx).compose(parked -> {
            if (parked.isEmpty()) {
                return Future.<Void>succeededFuture();
            }
            return host.waits(instance, tx)
                    .compose(waits -> waits.endedStatus() != null
                            ? commands.settleEnded(instance, tx).<Void>mapEmpty()
                            : atWaits(instance, waits.open(), 0, tx));
        });
    }

    private Future<Void> atWaits(WorkflowInstanceId instance, List<WaitAddress> open, int index, SqlClient tx) {
        if (index >= open.size()) {
            return Future.succeededFuture();
        }
        WaitAddress wait = open.get(index);
        return commands.parkedSignalsAt(instance, wait.signalName(), wait.forkStepId(), wait.branchId(), tx)
                .compose(parked -> guard.unblocked(instance, wait.stepId(), wait.branchId(), parked, tx))
                .compose(candidates -> candidates.isEmpty()
                        ? atWaits(instance, open, index + 1, tx)
                        : candidates(instance, candidates, 0, () -> atWaits(instance, open, index + 1, tx), tx));
    }

    private Future<Void> candidates(
            WorkflowInstanceId instance,
            List<ParkedSignal> candidates,
            int index,
            Supplier<Future<Void>> nextWait,
            SqlClient tx) {
        if (index >= candidates.size()) {
            return nextWait.get();
        }
        if (overBudget(tx)) {
            return requeue(candidates, index, tx);
        }
        ParkedSignal signal = candidates.get(index);
        return commands.readParked(instance, signal.commandId(), tx).compose(read -> {
            if (read.isEmpty()) {
                return candidates(instance, candidates, index + 1, nextWait, tx);
            }
            SegmentCommand command = read.get();
            return Savepoints.attempt(tx, () -> host.apply(command, tx)).compose(attempt -> switch (attempt) {
                case Savepoints.Attempt.Applied<Void> applied ->
                    commands.settleParked(signal.commandId(), "COMPLETE", null, tx)
                            .compose(ClaimLostException::requireRow)
                            .compose(done -> host.applied(command, tx))
                            // the instance moved: look at the waits it holds now
                            .compose(done -> drain(instance, tx));
                case Savepoints.Attempt.Contained<Void> contained ->
                    contained.kind() == Savepoints.Containment.STEP_LIMIT
                            ? requeue(candidates, index, tx)
                            : reject(instance, signal, contained.cause(), tx)
                                    .compose(done -> candidates(instance, candidates, index + 1, nextWait, tx));
            });
        });
    }

    private boolean overBudget(SqlClient tx) {
        return host.stepsUsed(tx) > host.stepCap(tx) / 2;
    }

    /** Sets the signal at {@code from} and every later one of the wait back to pending at their positions. */
    private Future<Void> requeue(List<ParkedSignal> candidates, int from, SqlClient tx) {
        Future<Void> chain = Future.succeededFuture();
        for (int i = from; i < candidates.size(); i++) {
            long id = candidates.get(i).commandId();
            chain = chain.compose(done -> commands.requeueParked(id, tx).mapEmpty());
        }
        return chain;
    }

    private Future<Void> reject(WorkflowInstanceId instance, ParkedSignal signal, Throwable cause, SqlClient tx) {
        log.warn(
                "Parked signal '{}' (command {}) of instance {} failed and was rejected: {}",
                signal.signalName(),
                signal.commandId(),
                instance.value(),
                cause.getClass().getName());
        return settleRejected(instance, signal, CommandErrors.signalFailure(signal.signalName()), tx);
    }

    private Future<Void> settleRejected(WorkflowInstanceId instance, ParkedSignal signal, String reason, SqlClient tx) {
        return commands.settleParked(signal.commandId(), "REJECTED", reason, tx)
                .compose(ClaimLostException::requireRow)
                .compose(done -> host.rejected(instance, signal.commandId(), signal.signalName(), reason, tx));
    }
}
