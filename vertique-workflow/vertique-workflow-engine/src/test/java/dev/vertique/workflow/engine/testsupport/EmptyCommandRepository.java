// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.testsupport;

import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandActor;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.DispositionResult;
import dev.vertique.workflow.engine.spi.InstanceFailureListener;
import dev.vertique.workflow.engine.spi.ParkedSignal;
import dev.vertique.workflow.engine.spi.QueueCounts;
import dev.vertique.workflow.engine.spi.RecordedTimer;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.SettledCommand;
import dev.vertique.workflow.engine.spi.TimerCommandSpec;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntToLongFunction;

/** A command repository of a database that records no commands, for engine tests that do not read the log. */
public final class EmptyCommandRepository implements WorkflowCommandRepository<SqlClient> {

    /** A signal command as {@link #recordSignal} was asked to record it. */
    public record RecordedSignal(
            WorkflowInstanceId instance,
            String signalName,
            @Nullable String forkStepId,
            @Nullable String branchId,
            String dedupKey,
            @Nullable String payloadJson,
            @Nullable String explicitCarrierJson) {}

    private final List<RecordedSignal> recordedSignals = new CopyOnWriteArrayList<>();
    private final List<TimerCommandSpec> recordedTimers = new CopyOnWriteArrayList<>();

    /**
     * The timers recorded through this repository, oldest first.
     *
     * @return a snapshot of the recordings
     */
    public List<TimerCommandSpec> recordedTimers() {
        return List.copyOf(recordedTimers);
    }

    /** A migration command as {@link #recordMigration} was asked to record it. */
    public record RecordedMigration(
            WorkflowInstanceId instance,
            long targetVersion,
            @Nullable String targetScope) {}

    private final List<WorkflowInstanceId> recordedRetries = new CopyOnWriteArrayList<>();
    private final List<RecordedMigration> recordedMigrations = new CopyOnWriteArrayList<>();
    private final List<WorkflowInstanceId> cancelledInstances = new CopyOnWriteArrayList<>();
    private volatile boolean deadLetters;
    private volatile Optional<List<String>> declaredSignalNames = Optional.empty();

    /**
     * The retries recorded through this repository, oldest first.
     *
     * @return a snapshot of the recordings
     */
    public List<WorkflowInstanceId> recordedRetries() {
        return List.copyOf(recordedRetries);
    }

    /**
     * The migrations recorded through this repository, oldest first.
     *
     * @return a snapshot of the recordings
     */
    public List<RecordedMigration> recordedMigrations() {
        return List.copyOf(recordedMigrations);
    }

    /**
     * The instances whose commands were cancelled through this repository, oldest first.
     *
     * @return a snapshot of the cancellations
     */
    public List<WorkflowInstanceId> cancelledInstances() {
        return List.copyOf(cancelledInstances);
    }

    /**
     * Makes {@link #hasDeadLetters} answer as given.
     *
     * @param deadLetters whether every instance holds a dead-lettered command
     */
    public void deadLetters(boolean deadLetters) {
        this.deadLetters = deadLetters;
    }

    /**
     * Makes {@link #declaredSignalNames} answer the given names for every wait, as a definition row that stores them.
     *
     * @param names the signal names every pinned definition declares
     */
    public void declaredSignalNames(List<String> names) {
        this.declaredSignalNames = Optional.of(List.copyOf(names));
    }

    @Override
    public Future<Long> recordRetry(WorkflowInstanceId instance, CommandActor actor, SqlClient tx) {
        recordedRetries.add(instance);
        return Future.succeededFuture(1L);
    }

    @Override
    public Future<Long> recordMigration(
            WorkflowInstanceId instance,
            long targetVersion,
            @Nullable String targetScope,
            CommandActor actor,
            SqlClient tx) {
        recordedMigrations.add(new RecordedMigration(instance, targetVersion, targetScope));
        return Future.succeededFuture(1L);
    }

    @Override
    public Future<Boolean> hasDeadLetters(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture(deadLetters);
    }

    @Override
    public Future<Optional<SegmentCommand>> readStart(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture(Optional.empty());
    }

    @Override
    public Future<Integer> supersedeDeadStart(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture(0);
    }

    @Override
    public Future<Integer> reofferDeadLetters(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture(0);
    }

    @Override
    public Future<Integer> settleEnded(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture(0);
    }

    @Override
    public Future<List<SettledCommand>> cancelAll(WorkflowInstanceId instance, CommandActor canceller, SqlClient tx) {
        cancelledInstances.add(instance);
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<Integer> cancel(long commandId, UUID token, String lastError, SqlClient tx) {
        return Future.succeededFuture(1);
    }

    @Override
    public Future<RecordedTimer> recordTimer(TimerCommandSpec timer, SqlClient tx) {
        recordedTimers.add(timer);
        Instant fireAt =
                switch (timer.fireTime()) {
                    case TimerCommandSpec.FireTime.At at -> at.at();
                    case TimerCommandSpec.FireTime.After after -> Instant.now().plus(after.delay());
                };
        return Future.succeededFuture(new RecordedTimer(UUID.randomUUID(), recordedTimers.size(), fireAt));
    }

    @Override
    public Future<Integer> rearmTimers(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture(0);
    }

    @Override
    public Future<List<String>> unsettledTargets(WorkflowInstanceId instance, String kind, SqlClient tx) {
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<List<UUID>> liveTasks(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture(List.of());
    }

    /**
     * The signal commands recorded through this repository, oldest first.
     *
     * @return a snapshot of the recordings
     */
    public List<RecordedSignal> recordedSignals() {
        return List.copyOf(recordedSignals);
    }

    @Override
    public Future<Integer> countRunnable(WorkflowInstanceId instance) {
        return Future.succeededFuture(0);
    }

    @Override
    public Future<List<PendingCommand>> findDeferred(WorkflowInstanceId instance) {
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<List<PendingCommand>> findParked(WorkflowInstanceId instance) {
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<List<PendingCommand>> findNotYetAvailable(WorkflowInstanceId instance) {
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<Void> lockInstance(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Boolean> tryLockInstance(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture(true);
    }

    @Override
    public Future<Long> nextQueueSequence(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture(1L);
    }

    @Override
    public Future<Long> recordStart(
            WorkflowInstanceId instance,
            String idempotencyKey,
            @Nullable String payloadJson,
            @Nullable String targetJson,
            CommandActor actor,
            SqlClient tx) {
        return Future.succeededFuture(1L);
    }

    @Override
    public Future<List<CommandHead>> peek(int limit) {
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<Optional<ClaimedCommand>> claim(
            CommandHead head,
            String claimedBy,
            int leaseSeconds,
            int maxAttempts,
            InstanceFailureListener<SqlClient> failures) {
        return Future.succeededFuture(Optional.empty());
    }

    @Override
    public Future<Optional<SegmentCommand>> fence(ClaimedCommand claim, SqlClient tx) {
        return Future.succeededFuture(Optional.empty());
    }

    @Override
    public Future<Integer> complete(long commandId, UUID token, SqlClient tx) {
        return Future.succeededFuture(1);
    }

    @Override
    public Future<Integer> reject(long commandId, UUID token, SqlClient tx) {
        return Future.succeededFuture(1);
    }

    @Override
    public Future<Integer> defer(CommandHead head) {
        return Future.succeededFuture(0);
    }

    @Override
    public Future<Boolean> release(ClaimedCommand claim) {
        return Future.succeededFuture(false);
    }

    @Override
    public Future<Integer> retryTransient(ClaimedCommand claim, IntToLongFunction backoffMs, String lastError) {
        return Future.succeededFuture(0);
    }

    @Override
    public Future<DispositionResult> failCommand(
            ClaimedCommand claim, String reasonCode, String lastError, InstanceFailureListener<SqlClient> failures) {
        return Future.succeededFuture(DispositionResult.CLAIM_LOST);
    }

    @Override
    public Future<List<PendingCommand>> findStuck(WorkflowInstanceId instance, long stuckAfterSeconds) {
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<QueueCounts> countQueue(long stuckAfterSeconds) {
        return Future.succeededFuture(new QueueCounts(0, 0, 0, 0));
    }

    @Override
    public Future<Integer> reject(long commandId, UUID token, String lastError, SqlClient tx) {
        return Future.succeededFuture(1);
    }

    @Override
    public Future<Integer> stale(long commandId, UUID token, String lastError, SqlClient tx) {
        return Future.succeededFuture(1);
    }

    @Override
    public Future<Integer> park(long commandId, UUID token, SqlClient tx) {
        return Future.succeededFuture(1);
    }

    @Override
    public Future<Optional<List<String>>> declaredSignalNames(
            DefinitionKey pin, @Nullable String forkStepId, @Nullable String branchId, SqlClient tx) {
        return Future.succeededFuture(declaredSignalNames);
    }

    @Override
    public Future<Boolean> raiseOpenSignals(WorkflowInstanceId instance, int limit, SqlClient tx) {
        return Future.succeededFuture(true);
    }

    @Override
    public Future<Void> lowerOpenSignals(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Long> recordCompletion(
            WorkflowInstanceId instance,
            UUID taskId,
            String dedupKey,
            @Nullable String payloadJson,
            String targetJson,
            CommandActor actor,
            SqlClient tx) {
        return Future.succeededFuture(1L);
    }

    @Override
    @Deprecated
    public Future<Long> recordSignal(
            WorkflowInstanceId instance,
            String signalName,
            @Nullable String forkStepId,
            @Nullable String branchId,
            String dedupKey,
            @Nullable String payloadJson,
            @Nullable String explicitCarrierJson,
            CommandActor actor,
            SqlClient tx) {
        return recordSignal(
                instance,
                signalName,
                forkStepId,
                branchId,
                null,
                dedupKey,
                payloadJson,
                explicitCarrierJson,
                null,
                actor,
                tx);
    }

    @Override
    public Future<Long> recordSignal(
            WorkflowInstanceId instance,
            String signalName,
            @Nullable String forkStepId,
            @Nullable String branchId,
            @Nullable String waitId,
            String dedupKey,
            @Nullable String payloadJson,
            @Nullable String explicitCarrierJson,
            @Nullable String provenanceJson,
            CommandActor actor,
            SqlClient tx) {
        recordedSignals.add(new RecordedSignal(
                instance, signalName, forkStepId, branchId, dedupKey, payloadJson, explicitCarrierJson));
        return Future.succeededFuture(1L);
    }

    @Override
    public Future<List<ParkedSignal>> parkedSignalsAt(
            WorkflowInstanceId instance,
            String signalName,
            @Nullable String forkStepId,
            @Nullable String branchId,
            SqlClient tx) {
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<List<ParkedSignal>> parkedSignals(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<Optional<SegmentCommand>> readParked(WorkflowInstanceId instance, long commandId, SqlClient tx) {
        return Future.succeededFuture(Optional.empty());
    }

    @Override
    public Future<Integer> settleParked(long commandId, String state, @Nullable String lastError, SqlClient tx) {
        return Future.succeededFuture(0);
    }

    @Override
    public Future<Integer> requeueParked(long commandId, SqlClient tx) {
        return Future.succeededFuture(0);
    }

    @Override
    public Future<Set<Long>> deadlineBlocked(
            WorkflowInstanceId instance,
            String waitStepId,
            @Nullable String branchId,
            Collection<Long> signals,
            SqlClient tx) {
        return Future.succeededFuture(Set.of());
    }

    @Override
    public Future<Boolean> wasParked(WorkflowInstanceId instance, long commandId, SqlClient tx) {
        return Future.succeededFuture(false);
    }
}
