// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.util.UUIDV7;
import dev.vertique.workflow.engine.executor.CommandErrors;
import dev.vertique.workflow.engine.spi.ParkedSignal;
import dev.vertique.workflow.engine.spi.SignalWaitRecord;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowSignalWaitRepository;
import dev.vertique.workflow.exception.WorkflowCorrelationKeyException;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.types.EchoedNames;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private collaborator that keeps the open wait records of the signal waits that declare a computed
 * correlation key.
 *
 * <p>A record is written in the transaction of the segment that moves the instance into the wait, so a segment that
 * rolls back leaves none. A wait that declares no key writes nothing. A computed key is a non-blank string of at most
 * 255 characters without a NUL; any other result, or an accessor that throws, fails the segment with a
 * {@link WorkflowCorrelationKeyException} before anything is written. No message, log line or failure of this class
 * repeats a key.
 *
 * <p>A record is deleted in the transaction that leaves its wait, by {@link #close} for one wait and by
 * {@link #closeAll} for an instance that ends. These are the only places a record is deleted. A record's id names one
 * entry of the wait: an entry that is entered again is a new one, so a signal that carries the id of an entry applies to
 * that entry only while its record is held. {@link #close} settles the signals parked on the entry it closes as stale;
 * {@link #closeAll} settles nothing, because the signals of an instance that ends settle as any signal does.
 */
@Singleton
final class SignalWaits {

    private static final Logger log = LoggerFactory.getLogger(SignalWaits.class);

    /** The fork step and branch of a wait on the main path, which the record's columns hold as empty strings. */
    private static final String MAIN_PATH = "";

    private final WorkflowSignalWaitRepository<SqlClient> waits;
    private final WorkflowCommandRepository<SqlClient> commands;
    private final WorkflowHistoryRecorder history;
    private final Clock clock;

    /**
     * Creates the collaborator.
     *
     * @param waits the repository the records are written to
     * @param commands the command repository whose parked signals a closing wait settles
     * @param history the history writer of the entries a settlement leaves
     * @param clock the clock that stamps a record
     */
    @Inject
    SignalWaits(
            WorkflowSignalWaitRepository<SqlClient> waits,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowHistoryRecorder history,
            Clock clock) {
        this.waits = waits;
        this.commands = commands;
        this.history = history;
        this.clock = clock;
    }

    /**
     * Writes the open record of an instance that enters a signal wait on the main path, when the wait declares a key.
     *
     * <p>The key is computed from the state as it stands on entry to the wait. The record carries the instance's start
     * scope, the contract name of the plan the instance is pinned to and the instance's definition id, under a new
     * version 7 wait id. A record left at the same address is replaced.
     *
     * @param instance the instance entering the wait, with the state it enters with
     * @param node the wait
     * @param plan the plan the instance is pinned to
     * @param tx the segment's transaction
     * @return a {@link Future} that completes when the record is written, or at once when the wait declares no key;
     *     it fails with {@link WorkflowCorrelationKeyException} when the computed key breaks the key rule, before
     *     anything is written
     */
    Future<Void> open(WorkflowInstance instance, WaitSignalNode node, RuntimeWorkflow plan, SqlClient tx) {
        return write(
                instance.id(),
                instance.scopeKey(),
                instance.definitionId(),
                instance.stateJson(),
                MAIN_PATH,
                MAIN_PATH,
                node,
                plan,
                tx);
    }

    /**
     * Writes the open record of a fork branch that enters a signal wait, when the wait declares a key.
     *
     * <p>The key is computed from the parent instance's state as the branch enters the wait. The record names the fork
     * step and the branch, and is otherwise as for {@link #open(WorkflowInstance, WaitSignalNode, RuntimeWorkflow,
     * SqlClient)}.
     *
     * @param instanceId the parent instance that owns the branch
     * @param scopeKey the start scope of the parent instance
     * @param definitionId the definition id of the parent instance
     * @param parentStateJson the parent instance's state as the branch enters the wait; {@code null} reads as an empty
     *     object
     * @param forkStepId the fork step the branch belongs to
     * @param branchId the branch that enters the wait
     * @param node the wait
     * @param plan the plan the instance is pinned to
     * @param tx the branch's transaction
     * @return a {@link Future} that completes when the record is written, or at once when the wait declares no key;
     *     it fails with {@link WorkflowCorrelationKeyException} when the computed key breaks the key rule, before
     *     anything is written
     */
    Future<Void> openBranch(
            WorkflowInstanceId instanceId,
            String scopeKey,
            String definitionId,
            String parentStateJson,
            String forkStepId,
            String branchId,
            WaitSignalNode node,
            RuntimeWorkflow plan,
            SqlClient tx) {
        return write(
                instanceId,
                scopeKey,
                definitionId,
                parentStateJson != null ? parentStateJson : "{}",
                forkStepId,
                branchId,
                node,
                plan,
                tx);
    }

    /**
     * Deletes the record of one wait, in the transaction that leaves it, and settles as stale the signals parked on the
     * entry it closes.
     *
     * <p>Deleting a record that is not there is not an error: a wait that declares no key has none, an older node's
     * cancel may have left one, and a wait entered again may have replaced one. When no record was held, no signal is
     * parked on the entry and nothing is settled.
     *
     * <p>Each parked signal that carries the id of an entry whose record is no longer held is settled {@code STALE}
     * in this transaction, with a {@code COMMAND_STALE} history entry, except the command named by
     * {@code applyingCommandId}: its own settlement belongs to the executor that applies it, as a signal's does. A
     * signal parked on an entry that stays open is left as it is. The settlement is written in the caller's
     * transaction, so a caller that runs inside a savepoint rolls it back with the rest of the savepoint.
     *
     * @param instance the instance that leaves the wait
     * @param stepId the step of the wait
     * @param forkStepId the fork step of the branch that leaves the wait; {@code null} for the main wait
     * @param branchId the branch that leaves the wait; {@code null} for the main wait
     * @param applyingCommandId the id of the command whose application leaves the wait, which is not settled, or
     *     {@code null} where no command applies
     * @param tx the transaction that leaves the wait
     * @return a {@link Future} of {@code true} when a record was deleted and {@code false} when none was held
     */
    Future<Boolean> close(
            WorkflowInstanceId instance,
            String stepId,
            @Nullable String forkStepId,
            @Nullable String branchId,
            @Nullable Long applyingCommandId,
            SqlClient tx) {
        return waits.delete(
                        instance,
                        stepId,
                        forkStepId != null ? forkStepId : MAIN_PATH,
                        branchId != null ? branchId : MAIN_PATH,
                        tx)
                .compose(deleted -> deleted
                        ? settleStale(instance, applyingCommandId, tx).map(settled -> true)
                        : Future.succeededFuture(false));
    }

    /**
     * Tells whether the entry with the given id is open for an instance: whether its record is held.
     *
     * @param instance the instance a signal addresses
     * @param waitId the id of the entry the signal carries
     * @param tx the active transaction
     * @return a {@link Future} of {@code true} when the entry is open for the instance
     */
    Future<Boolean> isOpen(WorkflowInstanceId instance, String waitId, SqlClient tx) {
        UUID id = entryId(waitId);
        if (id == null) {
            return Future.succeededFuture(false);
        }
        return waits.findById(id, tx)
                .map(found ->
                        found.filter(held -> held.instanceId().equals(instance)).isPresent());
    }

    /**
     * Reads the records an instance holds: the open entries of its waits.
     *
     * @param instance the instance
     * @param tx the active transaction
     * @return a {@link Future} of the records the instance holds
     */
    Future<List<SignalWaitRecord>> held(WorkflowInstanceId instance, SqlClient tx) {
        return waits.findByInstance(instance, tx);
    }

    /**
     * Settles as stale every parked signal of the instance that carries the id of an entry that is not open, except the
     * applying command, with the history of each.
     */
    private Future<Void> settleStale(WorkflowInstanceId instance, @Nullable Long applyingCommandId, SqlClient tx) {
        return commands.parkedSignals(instance, tx).compose(parked -> {
            List<ParkedSignal> correlated = parked.stream()
                    .filter(signal -> signal.waitId() != null)
                    .filter(signal -> applyingCommandId == null || signal.commandId() != applyingCommandId)
                    .toList();
            if (correlated.isEmpty()) {
                return Future.<Void>succeededFuture();
            }
            return waits.findByInstance(instance, tx).compose(held -> {
                Set<UUID> open = held.stream().map(SignalWaitRecord::waitId).collect(Collectors.toSet());
                Future<Void> chain = Future.succeededFuture();
                for (ParkedSignal signal : correlated) {
                    if (!open.contains(entryId(signal.waitId()))) {
                        chain = chain.compose(done -> settleStale(instance, signal, tx));
                    }
                }
                return chain;
            });
        });
    }

    /** The id of an entry as the record holds it, or {@code null} for a text that is not an id. */
    private static @Nullable UUID entryId(String waitId) {
        try {
            return UUID.fromString(waitId);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    private Future<Void> settleStale(WorkflowInstanceId instance, ParkedSignal signal, SqlClient tx) {
        String reason = CommandErrors.signalWaitEntryClosed(signal.signalName());
        return commands.settleParked(signal.commandId(), "STALE", reason, tx)
                .compose(rows -> rows == 0
                        ? Future.<Void>succeededFuture()
                        : history.appendSignalCommandHistory(
                                        instance,
                                        WorkflowEntryType.COMMAND_STALE,
                                        signal.commandId(),
                                        signal.signalName(),
                                        reason,
                                        tx)
                                .onSuccess(done -> log.info(
                                        "Signal '{}' (command {}) of instance {} settled stale: its wait entry closed",
                                        EchoedNames.bounded(signal.signalName()),
                                        signal.commandId(),
                                        instance.value())));
    }

    /**
     * Deletes every record of an instance that ends or is cancelled, the records of branches still open included.
     *
     * @param instance the instance that ends
     * @param tx the transaction that ends it
     * @return a {@link Future} that completes when the records are deleted
     */
    Future<Void> closeAll(WorkflowInstanceId instance, SqlClient tx) {
        return waits.deleteAll(instance, tx);
    }

    private Future<Void> write(
            WorkflowInstanceId instanceId,
            String scopeKey,
            String definitionId,
            String stateJson,
            String forkStepId,
            String branchId,
            WaitSignalNode node,
            RuntimeWorkflow plan,
            SqlClient tx) {
        if (node.correlationKeyCallbackId() == null) {
            return Future.succeededFuture();
        }
        String key;
        try {
            key = keyOf(definitionId, stateJson, node, plan);
        } catch (WorkflowCorrelationKeyException refused) {
            log.warn("Wait record refused: {}", refused.getMessage());
            return Future.failedFuture(refused);
        }
        return waits.write(
                new SignalWaitRecord(
                        UUIDV7.randomUUID(),
                        instanceId,
                        node.stepId(),
                        forkStepId,
                        branchId,
                        scopeKey,
                        plan.contractName(),
                        definitionId,
                        node.signalName(),
                        key,
                        clock.instant()),
                tx);
    }

    /**
     * Computes the key of the wait through the node's callback and applies the key rule. A refusal names the definition,
     * the step and the reason, and never the key or the callback's own failure.
     */
    private static String keyOf(String definitionId, String stateJson, WaitSignalNode node, RuntimeWorkflow plan) {
        String key;
        try {
            Object state = Json.decodeValue(Buffer.buffer(stateJson), plan.stateType());
            @SuppressWarnings("unchecked")
            Function<Object, String> resolver = (Function<Object, String>)
                    (Function<?, ?>) plan.callbacks().correlationKeyResolver(node.correlationKeyCallbackId());
            key = resolver.apply(state);
        } catch (RuntimeException failed) {
            throw refusal(definitionId, node, "the key accessor failed");
        }
        if (key == null) {
            throw refusal(definitionId, node, "the key is null");
        }
        if (key.isBlank()) {
            throw refusal(definitionId, node, "the key is blank");
        }
        if (key.length() > CorrelatedSignal.MAX_KEY_LENGTH) {
            throw refusal(
                    definitionId, node, "the key is longer than " + CorrelatedSignal.MAX_KEY_LENGTH + " characters");
        }
        if (key.indexOf('\u0000') >= 0) {
            throw refusal(definitionId, node, "the key contains a NUL character");
        }
        return key;
    }

    private static WorkflowCorrelationKeyException refusal(String definitionId, WaitSignalNode node, String reason) {
        return new WorkflowCorrelationKeyException(definitionId, node.stepId(), reason);
    }
}
