// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.util.UUIDV7;
import dev.vertique.workflow.engine.spi.SignalWaitRecord;
import dev.vertique.workflow.engine.spi.WorkflowSignalWaitRepository;
import dev.vertique.workflow.exception.WorkflowCorrelationKeyException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.function.Function;
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
 */
@Singleton
final class SignalWaits {

    private static final Logger log = LoggerFactory.getLogger(SignalWaits.class);

    /** The fork step and branch of a wait on the main path, which the record's columns hold as empty strings. */
    private static final String MAIN_PATH = "";

    /** The longest key a wait accepts, in characters: the width of the column that holds it. */
    private static final int MAX_KEY_LENGTH = 255;

    private final WorkflowSignalWaitRepository<SqlClient> waits;
    private final Clock clock;

    /**
     * Creates the collaborator.
     *
     * @param waits the repository the records are written to
     * @param clock the clock that stamps a record
     */
    @Inject
    SignalWaits(WorkflowSignalWaitRepository<SqlClient> waits, Clock clock) {
        this.waits = waits;
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
        if (key.length() > MAX_KEY_LENGTH) {
            throw refusal(definitionId, node, "the key is longer than " + MAX_KEY_LENGTH + " characters");
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
