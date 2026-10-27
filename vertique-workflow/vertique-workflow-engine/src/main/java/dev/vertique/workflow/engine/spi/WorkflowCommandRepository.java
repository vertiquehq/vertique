// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntToLongFunction;

/**
 * Repository SPI for the recorded command log.
 *
 * <p>Generic over the transaction-handle type {@code TX} like the other repositories, so the engine does not bind to
 * a database client.
 *
 * <p>The read members open no transaction on a caller's connection and hold no lock: each borrows one pooled
 * connection for one statement and returns it. They judge eligibility against the database clock, through the one
 * eligibility rule of the executor protocol: a command is claimable when it is at the head of its queue, not held
 * back and available.
 *
 * <p>Every write that takes a {@code SIGNAL} command out of its open states ({@code PENDING}, {@code DEFERRED},
 * {@code CLAIMED}, {@code PARKED}) lowers the instance's open-signal count in the same statement; moves between open
 * states leave it unchanged.
 *
 * <p>The lock and sequence members run inside a caller's transaction. Every transaction that changes an instance
 * takes the instance's execution lock before its first workflow change, and holds it until it ends, so one
 * transaction at a time changes an instance and appends to its history.
 *
 * @param <TX> the transaction-handle type threaded through transactional operations
 */
public interface WorkflowCommandRepository<TX> {

    /**
     * Counts the commands that keep an instance from being idle: commands in flight, that is claimed under a live
     * lease, and commands that are claimable now.
     *
     * @param instance the instance to count, or {@code null} for every instance
     * @return a {@link Future} containing the number of runnable commands
     */
    Future<Integer> countRunnable(@Nullable WorkflowInstanceId instance);

    /**
     * Lists the deferred commands that are not claimable now, ordered by instance and sequence.
     *
     * @param instance the instance to read, or {@code null} for every instance
     * @return a {@link Future} containing the commands
     */
    Future<List<PendingCommand>> findDeferred(@Nullable WorkflowInstanceId instance);

    /**
     * Lists the parked commands, ordered by instance and sequence.
     *
     * @param instance the instance to read, or {@code null} for every instance
     * @return a {@link Future} containing the commands
     */
    Future<List<PendingCommand>> findParked(@Nullable WorkflowInstanceId instance);

    /**
     * Lists the pending and claimed commands that are not claimable now, ordered by instance and sequence: timers
     * before their fire time, backed-off commands, commands behind a blocking head and held-back timers.
     *
     * @param instance the instance to read, or {@code null} for every instance
     * @return a {@link Future} containing the commands
     */
    Future<List<PendingCommand>> findNotYetAvailable(@Nullable WorkflowInstanceId instance);

    /**
     * Reads the database-relative delay to the earliest future availability among pending or deferred commands.
     * Implementations that do not provide this scheduling hint may return empty; executor polling remains the
     * fallback. This read is separate from the bounded diagnostic lists returned for {@code IdleReport}.
     *
     * @return a {@link Future} containing the nonnegative delay in milliseconds, or empty when no such command exists
     */
    default Future<Optional<Long>> findEarliestAvailabilityDelayMs() {
        return Future.succeededFuture(Optional.empty());
    }

    /**
     * Lists the commands reported stuck, ordered by instance and sequence: every dead-lettered command, and every
     * unsettled command whose first availability is older than {@code stuckAfterSeconds}. The first availability of a
     * timer command is its fire time and of any other command the time it was recorded, never its current
     * availability, which deferral and transient backoff move forward. Reads each command's last error as stored,
     * bounded to its column limit.
     *
     * @param instance          the instance to read, or {@code null} for every instance
     * @param stuckAfterSeconds the age after which an unsettled command is reported stuck, in seconds
     * @return a {@link Future} containing the commands
     */
    Future<List<PendingCommand>> findStuck(@Nullable WorkflowInstanceId instance, long stuckAfterSeconds);

    /**
     * Counts the queue for the executor's queue snapshot: the commands claimable now, those reported stuck (as
     * {@link #findStuck}), the parked ones and the dead-lettered ones, over every instance.
     *
     * @param stuckAfterSeconds the age after which an unsettled command is reported stuck, in seconds
     * @return a {@link Future} containing the counts
     */
    Future<QueueCounts> countQueue(long stuckAfterSeconds);

    /**
     * Takes the instance's execution lock for the caller's transaction, waiting for another transaction that holds it
     * for at most the configured lock timeout. The timeout applies to this one acquisition; the caller's own
     * {@code lock_timeout} is unchanged afterwards. Taking a lock the transaction already holds returns at once.
     *
     * <p>The lock is released when the transaction ends. A wait that times out, or a deadlock between transactions,
     * fails the future with a retryable persistence failure, and the database aborts the transaction.
     *
     * @param instance the instance to lock; it need not exist yet
     * @param tx       the caller's transaction handle
     * @return a {@link Future} that completes once the transaction holds the lock
     */
    Future<Void> lockInstance(WorkflowInstanceId instance, TX tx);

    /**
     * Takes the instance's execution lock for the caller's transaction only if it is free, and never waits.
     *
     * @param instance the instance to lock
     * @param tx       the caller's transaction handle
     * @return a {@link Future} containing {@code true} when the transaction now holds the lock and {@code false} when
     *     another transaction holds it
     */
    Future<Boolean> tryLockInstance(WorkflowInstanceId instance, TX tx);

    /**
     * Allocates the next position in the instance's command queue. The queue row is created on first touch, so the
     * first allocation is 1; a rolled-back transaction leaves no trace and its position is allocated again.
     *
     * <p>The caller must hold the instance's execution lock; the allocation is not safe without it.
     *
     * @param instance the instance whose queue advances
     * @param tx       the caller's transaction handle
     * @return a {@link Future} containing the allocated position
     */
    Future<Long> nextQueueSequence(WorkflowInstanceId instance, TX tx);

    /**
     * Records a {@code START} command for a new instance in the caller's transaction: the next position of the
     * instance's queue, state {@code PENDING}, recorded and available at the database clock now, with the start
     * payload stored as the command's payload.
     *
     * <p>The caller must hold the instance's execution lock.
     *
     * @param instance       the new instance
     * @param idempotencyKey the caller's idempotency key, kept for display
     * @param payloadJson    the start payload as JSON, or {@code null} when the start has none
     * @param targetJson     the caller-supplied subject reference of the start as JSON, or {@code null} when the
     *                       caller supplied none
     * @param tx             the caller's transaction handle
     * @return a {@link Future} containing the command's row id
     */
    Future<Long> recordStart(
            WorkflowInstanceId instance,
            String idempotencyKey,
            @Nullable String payloadJson,
            @Nullable String targetJson,
            TX tx);

    /**
     * Reads, without locks, the commands an executor could claim now, oldest availability first. Judges eligibility
     * on the database clock through the one eligibility rule.
     *
     * @param limit the most heads to return; at least 1
     * @return a {@link Future} containing the heads
     */
    Future<List<CommandHead>> peek(int limit);

    /**
     * Claims a peeked command in one short transaction of its own: tries the instance's execution lock, locks the
     * command row and checks again that it is claimable. Then, in this order:
     *
     * <ol>
     *   <li>when the command's instance has ended, settles the command per the outcome table with its history entry,
     *       spends no attempt, never dead-letters it and leaves the instance as it is
     *       ({@link ClaimedCommand.Result#SETTLED_INSTANCE_ENDED});</li>
     *   <li>otherwise, when the command has spent {@code maxAttempts} attempts, dead-letters it and leaves its
     *       attempts unchanged; a {@code START} also fails its instance, with history, in the same transaction
     *       ({@link ClaimedCommand.Result#DEAD_LETTERED});</li>
     *   <li>otherwise spends an attempt, sets a new token and a lease and marks it {@code CLAIMED}
     *       ({@link ClaimedCommand.Result#CLAIMED}).</li>
     * </ol>
     *
     * <p>When the lock is busy or the command is no longer claimable, nothing is written.
     *
     * @param head         the peeked head
     * @param claimedBy    the executor instance that claims
     * @param leaseSeconds how long the claim holds the command
     * @param maxAttempts  the attempts a command may spend before it is dead-lettered
     * @param failures     called in the claim's transaction when the claim fails an instance
     * @return a {@link Future} containing what the claim came to, or empty when the lock was busy or the command was
     *     not claimable
     */
    Future<Optional<ClaimedCommand>> claim(
            CommandHead head,
            String claimedBy,
            int leaseSeconds,
            int maxAttempts,
            InstanceFailureListener<TX> failures);

    /**
     * Claims a peeked command like the five-argument overload, with nobody told when an instance fails.
     *
     * @param head         the peeked head
     * @param claimedBy    the executor instance that claims
     * @param leaseSeconds how long the claim holds the command
     * @param maxAttempts  the attempts a command may spend before it is dead-lettered
     * @return a {@link Future} as for the five-argument overload
     */
    default Future<Optional<ClaimedCommand>> claim(
            CommandHead head, String claimedBy, int leaseSeconds, int maxAttempts) {
        return claim(head, claimedBy, leaseSeconds, maxAttempts, InstanceFailureListener.none());
    }

    /**
     * Claims a peeked command with no limit on its attempts, so it is never dead-lettered.
     *
     * @param head         the peeked head
     * @param claimedBy    the executor instance that claims
     * @param leaseSeconds how long the claim holds the command
     * @return a {@link Future} as for {@link #claim(CommandHead, String, int, int)}
     */
    default Future<Optional<ClaimedCommand>> claim(CommandHead head, String claimedBy, int leaseSeconds) {
        return claim(head, claimedBy, leaseSeconds, Integer.MAX_VALUE);
    }

    /**
     * Defers a peeked command that this node cannot run, in one short transaction of its own: tries the instance's
     * execution lock, locks the command row and checks that the command is still claimable, is not already deferred
     * into the future, and that the instance is still pinned to the plan the head observed (definition key and plan
     * hash). Then sets it {@code DEFERRED}, raises its deferral count by one and moves its availability forward by
     * five seconds doubled once per earlier deferral, at most five minutes. Attempts, token and lease are untouched. The
     * first deferral of a command (count {@code 0} to {@code 1}) also appends one {@code COMMAND_DEFERRED} history
     * entry that names the kind and the sequence and never the payload.
     *
     * <p>When the lock is busy or a check fails, nothing is written.
     *
     * @param head the peeked head, whose pin is the observation to check
     * @return a {@link Future} containing the new deferral count, or {@code 0} when nothing was written
     */
    Future<Integer> defer(CommandHead head);

    /**
     * Locks the claimed command's row and checks, in the segment's transaction, that the claim still stands: the
     * command is {@code CLAIMED} with this token and at the head of its queue. When it does not, nothing is written.
     *
     * <p>The caller must hold the instance's execution lock.
     *
     * @param claim the claim
     * @param tx    the segment's transaction handle
     * @return a {@link Future} containing the command, or empty when the fence failed
     */
    Future<Optional<SegmentCommand>> fence(ClaimedCommand claim, TX tx);

    /**
     * Completes the claimed command in the segment's transaction: state {@code COMPLETE}, token and lease cleared,
     * settled now. The write is fenced on the command being {@code CLAIMED} with this token. A completed {@code SIGNAL}
     * frees its slot of the instance's open-signal count in the same statement.
     *
     * @param commandId the command's row id
     * @param token     the claim token
     * @param tx        the segment's transaction handle
     * @return a {@link Future} containing the number of rows updated; {@code 0} means the claim no longer stands
     */
    Future<Integer> complete(long commandId, UUID token, TX tx);

    /**
     * Settles the claimed command as rejected, in the segment's transaction, because it no longer applies. The write
     * is fenced like {@link #complete}, and frees a {@code SIGNAL}'s open-signal slot.
     *
     * @param commandId the command's row id
     * @param token     the claim token
     * @param tx        the segment's transaction handle
     * @return a {@link Future} containing the number of rows updated
     */
    Future<Integer> reject(long commandId, UUID token, TX tx);

    /**
     * Releases a claim whose segment did not run, in one statement that takes no execution lock: back to
     * {@code PENDING}, the spent attempt restored, token and lease cleared. Writes only while the command is still
     * {@code CLAIMED} with this token.
     *
     * @param claim the claim to release
     * @return a {@link Future} containing {@code true} when the claim was released and {@code false} when it no
     *     longer stood
     */
    Future<Boolean> release(ClaimedCommand claim);

    /**
     * Schedules a retry of a claimed command whose segment failed transiently, in one transaction that locks only the
     * command row, without the execution lock: back to {@code PENDING}, the spent attempt restored, the transient
     * count raised by one, availability moved forward by the backoff of the new count, token and lease cleared and the
     * last error stored. Writes only while the command is still {@code CLAIMED} with this token.
     *
     * @param claim     the claim
     * @param backoffMs the pause in milliseconds before the retry, from the new transient count
     * @param lastError the sanitized, bounded reason to store
     * @return a {@link Future} containing the new transient count, or {@code 0} when the claim no longer stood
     */
    Future<Integer> retryTransient(ClaimedCommand claim, IntToLongFunction backoffMs, String lastError);

    /**
     * Settles a claimed command as failed after its segment rolled back with a failure that a retry would repeat, in
     * one transaction of its own that tries the execution lock without waiting. Locks the command row and writes only
     * while it is still {@code CLAIMED} with this token: the command is {@code FAILED} with token and lease cleared
     * and the last error stored, and its instance is {@code FAILED} with the reason and a history entry. A settled
     * command has no token, so a late call writes nothing.
     *
     * @param claim       the claim
     * @param reasonCode  the reason code stored on the instance
     * @param lastError   the sanitized, bounded reason to store on the command and the instance
     * @param failures    called in the transaction when the instance is failed, after its rows are written
     * @return a {@link Future} containing what the call came to
     */
    Future<DispositionResult> failCommand(
            ClaimedCommand claim, String reasonCode, String lastError, InstanceFailureListener<TX> failures);

    /**
     * Settles the claimed command as rejected with a reason, in the segment's transaction. The write is fenced like
     * {@link #complete}. A rejected {@code SIGNAL} frees its slot of the instance's open-signal count in the same
     * statement.
     *
     * @param commandId the command's row id
     * @param token     the claim token
     * @param lastError the sanitized, bounded reason to store
     * @param tx        the segment's transaction handle
     * @return a {@link Future} containing the number of rows updated; {@code 0} means the claim no longer stands
     */
    Future<Integer> reject(long commandId, UUID token, String lastError, TX tx);

    /**
     * Settles the claimed command as stale with a reason, in the segment's transaction: it addresses something that is
     * no longer there. The write is fenced like {@link #complete}, and frees a {@code SIGNAL}'s open-signal slot.
     *
     * @param commandId the command's row id
     * @param token     the claim token
     * @param lastError the sanitized, bounded reason to store
     * @param tx        the segment's transaction handle
     * @return a {@link Future} containing the number of rows updated
     */
    Future<Integer> stale(long commandId, UUID token, String lastError, TX tx);

    /**
     * Parks the claimed command in the segment's transaction: {@code PARKED}, token and lease cleared and the attempt
     * its claim spent restored, because the claim ended neither by crash nor by lease overrun. A parked command blocks
     * nothing and keeps its position and its slot of the open-signal count. The write is fenced like {@link #complete}.
     *
     * @param commandId the command's row id
     * @param token     the claim token
     * @param tx        the segment's transaction handle
     * @return a {@link Future} containing the number of rows updated
     */
    Future<Integer> park(long commandId, UUID token, TX tx);

    /**
     * Reads, in the caller's transaction, the signal names the pinned definition row declares for the addressed wait:
     * the instance's waits, or the waits of one branch of one fork.
     *
     * @param pin        the definition row the instance is pinned to
     * @param forkStepId the fork step of a branch-addressed signal, or {@code null}
     * @param branchId   the branch of a branch-addressed signal, or {@code null}
     * @param tx         the caller's transaction handle
     * @return a {@link Future} containing the declared names, empty when the row stores no declaration (a row written
     *     before names were stored, or no row) and a list that lacks the address's names when the row declares none for it
     */
    Future<Optional<List<String>>> declaredSignalNames(
            DefinitionKey pin, @Nullable String forkStepId, @Nullable String branchId, TX tx);

    /**
     * Raises the instance's open-signal count by one while it is below the limit, in the caller's transaction, with one
     * conditional update so that the limit holds under any isolation level. Creates the instance's queue row first when
     * an instance from before the command log has none.
     *
     * @param instance the instance
     * @param limit    the most open signals an instance may hold
     * @param tx       the caller's transaction handle
     * @return a {@link Future} containing {@code true} when the count was raised and {@code false} when the instance
     *     already holds {@code limit} open signals
     */
    Future<Boolean> raiseOpenSignals(WorkflowInstanceId instance, int limit, TX tx);

    /**
     * Lowers the instance's open-signal count by one, in the caller's transaction. Undoes a
     * {@link #raiseOpenSignals} whose recording did not go on.
     *
     * @param instance the instance
     * @param tx       the caller's transaction handle
     * @return a {@link Future} that completes when the count is lowered
     */
    Future<Void> lowerOpenSignals(WorkflowInstanceId instance, TX tx);

    /**
     * Records a {@code SIGNAL} command in the caller's transaction: the next position of the instance's queue, state
     * {@code PENDING}, recorded and available at the database clock now, with the payload and the explicit carrier
     * stored as recorded and the name and address stored as the command's target.
     *
     * <p>The caller must hold the instance's execution lock and have raised the open-signal count.
     *
     * @param instance            the instance
     * @param signalName          the signal's name
     * @param forkStepId          the fork step of a branch-addressed signal, or {@code null}
     * @param branchId            the branch of a branch-addressed signal, or {@code null}
     * @param dedupKey            the caller's idempotency key, kept for display
     * @param payloadJson         the payload as JSON, or {@code null} when the signal has none
     * @param explicitCarrierJson the sanitized explicit durable-context carrier as JSON, or {@code null}
     * @param tx                  the caller's transaction handle
     * @return a {@link Future} containing the command's row id
     */
    Future<Long> recordSignal(
            WorkflowInstanceId instance,
            String signalName,
            @Nullable String forkStepId,
            @Nullable String branchId,
            String dedupKey,
            @Nullable String payloadJson,
            @Nullable String explicitCarrierJson,
            TX tx);

    /**
     * Records a {@code COMPLETION} command in the caller's transaction: the next position of the instance's queue,
     * state {@code PENDING}, recorded and available at the database clock now, owning the task it completes. The
     * payload is stored as the command's payload and the decision, the reviewed subject version and the completing
     * actor as its target.
     *
     * <p>The caller must hold the instance's execution lock.
     *
     * @param instance    the instance
     * @param taskId      the task the completion reserves
     * @param dedupKey    the caller's idempotency key, kept for display
     * @param payloadJson the decision payload as JSON, or {@code null} when the decision has none
     * @param targetJson  the decision, reviewed subject version and completing actor as JSON
     * @param tx          the caller's transaction handle
     * @return a {@link Future} containing the command's row id
     */
    Future<Long> recordCompletion(
            WorkflowInstanceId instance,
            UUID taskId,
            String dedupKey,
            @Nullable String payloadJson,
            String targetJson,
            TX tx);

    /**
     * Records a timer in the caller's transaction: a {@code workflow_timers} row linked by the command's id and the
     * {@code TIMER} command that fires it. The command takes the next position of the instance's queue, state
     * {@code PENDING}; its fire time is read once from the database clock, is the command's availability and never
     * changes. A {@code TIMEOUT} command stores the wait it belongs to as its target: the step, the branch address of
     * a branch wait and the signal names the wait accepts. The timer row's metadata is the durable context of the
     * recording drive, signed for the timer's own carrier and without the identity-snapshot namespace; the command
     * stores no context.
     *
     * <p>The caller must hold the instance's execution lock.
     *
     * @param timer the timer to record
     * @param tx    the caller's transaction handle
     * @return a {@link Future} containing the timer's id, its command's row id and its fire time
     */
    Future<RecordedTimer> recordTimer(TimerCommandSpec timer, TX tx);

    /**
     * Lists the parked {@code SIGNAL} commands of an instance that carry the given name and address, in queue order.
     *
     * @param instance   the instance
     * @param signalName the signal's name
     * @param forkStepId the fork step of a branch-addressed signal, or {@code null} for instance-addressed ones
     * @param branchId   the branch of a branch-addressed signal, or {@code null} for instance-addressed ones
     * @param tx         the segment's transaction handle
     * @return a {@link Future} containing the parked signals
     */
    Future<List<ParkedSignal>> parkedSignalsAt(
            WorkflowInstanceId instance,
            String signalName,
            @Nullable String forkStepId,
            @Nullable String branchId,
            TX tx);

    /**
     * Lists every parked {@code SIGNAL} command of an instance, in queue order.
     *
     * @param instance the instance
     * @param tx       the segment's transaction handle
     * @return a {@link Future} containing the parked signals
     */
    Future<List<ParkedSignal>> parkedSignals(WorkflowInstanceId instance, TX tx);

    /**
     * Reads a parked command's payload, target, key and explicit carrier, in the segment's transaction.
     *
     * @param instance  the instance
     * @param commandId the command's row id
     * @param tx        the segment's transaction handle
     * @return a {@link Future} containing the command, or empty when it is no longer parked
     */
    Future<Optional<SegmentCommand>> readParked(WorkflowInstanceId instance, long commandId, TX tx);

    /**
     * Settles a parked command in the segment's transaction, with no claim token: a guarded {@code PARKED} to
     * {@code COMPLETE}, {@code REJECTED} or {@code STALE} transition that frees a {@code SIGNAL}'s open-signal slot.
     *
     * @param commandId the command's row id
     * @param state     the settled state
     * @param lastError the sanitized, bounded reason to store, or {@code null} to store none
     * @param tx        the segment's transaction handle
     * @return a {@link Future} containing the number of rows updated; {@code 0} means the command was not parked
     */
    Future<Integer> settleParked(long commandId, String state, @Nullable String lastError, TX tx);

    /**
     * Sets a parked command back to {@code PENDING} in the segment's transaction, with no claim token: it keeps its
     * position, id, attempts, transient count and deferral count, is available no later than now and keeps its slot of
     * the open-signal count.
     *
     * @param commandId the command's row id
     * @param tx        the segment's transaction handle
     * @return a {@link Future} containing the number of rows updated; {@code 0} means the command was not parked
     */
    Future<Integer> requeueParked(long commandId, TX tx);

    /**
     * Answers which of the given parked signals are deadline-blocked at a wait: a timeout command of that wait whose
     * fire time is at or before the signal's recording time and that is still pending, deferred, claimed or
     * dead-lettered.
     *
     * @param instance   the instance
     * @param waitStepId the step of the wait
     * @param branchId   the branch of a branch wait, or {@code null} for an instance wait
     * @param signals    the row ids of the parked signals to check
     * @param tx         the segment's transaction handle
     * @return a {@link Future} containing the ids of the deadline-blocked signals
     */
    Future<Set<Long>> deadlineBlocked(
            WorkflowInstanceId instance, String waitStepId, @Nullable String branchId, Collection<Long> signals, TX tx);

    /**
     * Tells whether a signal command was parked or dead-lettered before: whether the instance's history holds a parked
     * or a dead-lettered entry for it. A signal that was parked and set back to pending, or dead-lettered and offered
     * again by a retry, is applied from that state when it runs.
     *
     * @param instance  the instance
     * @param commandId the command's row id
     * @param tx        the segment's transaction handle
     * @return a {@link Future} containing {@code true} when the command was parked or dead-lettered at least once
     */
    Future<Boolean> wasParked(WorkflowInstanceId instance, long commandId, TX tx);

    /**
     * Records a {@code RETRY} command in the caller's transaction: the next position of the instance's queue, state
     * {@code PENDING}, recorded and available at the database clock now.
     *
     * <p>The caller must hold the instance's execution lock.
     *
     * @param instance the instance to recover
     * @param tx       the caller's transaction handle
     * @return a {@link Future} containing the command's row id
     */
    Future<Long> recordRetry(WorkflowInstanceId instance, TX tx);

    /**
     * Records a {@code MIGRATION} command in the caller's transaction: the next position of the instance's queue, state
     * {@code PENDING}, recorded and available at the database clock now, with the target version, and the target scope
     * when one was named, stored as the command's target.
     *
     * <p>The caller must hold the instance's execution lock.
     *
     * @param instance      the instance to migrate
     * @param targetVersion the definition version to migrate to
     * @param targetScope   the scope of the definition to migrate to, or {@code null} for the scope the instance is
     *                      pinned to
     * @param tx            the caller's transaction handle
     * @return a {@link Future} containing the command's row id
     */
    Future<Long> recordMigration(WorkflowInstanceId instance, long targetVersion, @Nullable String targetScope, TX tx);

    /**
     * Tells whether the instance holds a dead-lettered command, in the caller's transaction.
     *
     * @param instance the instance
     * @param tx       the caller's transaction handle
     * @return a {@link Future} containing {@code true} when at least one command of the instance is {@code DEAD}
     */
    Future<Boolean> hasDeadLetters(WorkflowInstanceId instance, TX tx);

    /**
     * Reads the {@code START} command of an instance, in the segment's transaction: its payload, subject target and
     * key, whatever state it is in.
     *
     * @param instance the instance
     * @param tx       the segment's transaction handle
     * @return a {@link Future} containing the command, or empty when the instance has none
     */
    Future<Optional<SegmentCommand>> readStart(WorkflowInstanceId instance, TX tx);

    /**
     * Settles the instance's dead-lettered {@code START} command as {@code SUPERSEDED}, with its history entry, in the
     * segment's transaction, because a retry initializes the instance from the stored start payload instead. A start
     * settled in any other state is left as it is.
     *
     * @param instance the instance
     * @param tx       the segment's transaction handle
     * @return a {@link Future} containing the number of commands superseded
     */
    Future<Integer> supersedeDeadStart(WorkflowInstanceId instance, TX tx);

    /**
     * Offers the instance's dead-lettered commands again, in the segment's transaction: each goes from {@code DEAD}
     * back to {@code PENDING} with its attempts reset to zero and its queue position unchanged, and is available now.
     * Each re-offered {@code SIGNAL} raises the instance's open-signal count by one in the same statement, without the
     * limit check, because the signal was counted when it was recorded and released when it was dead-lettered. A
     * re-offered {@code COMPLETION} still owns its task's reservation.
     *
     * @param instance the instance
     * @param tx       the segment's transaction handle
     * @return a {@link Future} containing the number of commands offered again
     */
    Future<Integer> reofferDeadLetters(WorkflowInstanceId instance, TX tx);

    /**
     * Settles the dead-lettered commands of an instance that has ended as {@code REJECTED}, with a history entry each,
     * in the segment's transaction; a dead-lettered completion also cancels the task it still reserves. Writes nothing
     * while the instance has not ended. A signal's open-signal slot was released when it was dead-lettered, so nothing
     * is lowered again.
     *
     * @param instance the instance
     * @param tx       the segment's transaction handle
     * @return a {@link Future} containing the number of commands rejected
     */
    Future<Integer> rejectDeadLetters(WorkflowInstanceId instance, TX tx);
}
